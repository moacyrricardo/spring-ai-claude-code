package com.iskeru.springai.claudecode.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.iskeru.springai.claudecode.ClaudeCodeException;

/**
 * Keeps individual command-line arguments under the operating system's per-argument
 * ceiling.
 *
 * <p>
 * Linux caps a single {@code argv} entry at {@code MAX_ARG_STRLEN} — 128 KiB — no matter
 * how much room the far larger total {@code ARG_MAX} leaves. A long system prompt is the
 * realistic way to reach it, and {@code exec} then fails with an opaque
 * {@code error=7, Argument list too long}. The CLI reads the same values from a file, so
 * an oversized argument is written to a temporary file and its flag swapped for the
 * {@code -file} variant.
 *
 * <p>
 * This is purely a transport detail. The CLI receives identical content, and because
 * {@link ClaudeCodeCliRequest} itself is untouched, spilling can never change a fixture
 * key — a prompt replays the same whether or not it happened to spill.
 */
final class ArgumentSpill {

	/**
	 * Linux {@code MAX_ARG_STRLEN} (32 pages). An argument must be strictly shorter than
	 * this, since the kernel counts the NUL terminator.
	 */
	static final int MAX_ARGUMENT_BYTES = 131_072;

	/** Flags whose value the CLI will equally accept from a file. */
	private static final Map<String, String> FILE_VARIANTS = Map.of("--system-prompt", "--system-prompt-file",
			"--append-system-prompt", "--append-system-prompt-file");

	private ArgumentSpill() {
	}

	/**
	 * Rewrites {@code command} so that no argument exceeds {@code threshold} bytes.
	 * @param createdFiles collects the temporary files written, for the caller to delete
	 * once the process has exited
	 * @return the rewritten command, or the original list when nothing needed spilling
	 */
	static List<String> apply(List<String> command, int threshold, List<Path> createdFiles) {
		if (command.stream().noneMatch(argument -> oversized(argument, threshold))) {
			return command;
		}

		List<String> spilled = new ArrayList<>(command.size());
		for (int i = 0; i < command.size(); i++) {
			String argument = command.get(i);
			String fileVariant = FILE_VARIANTS.get(argument);
			boolean hasValue = fileVariant != null && i + 1 < command.size();

			if (hasValue && oversized(command.get(i + 1), threshold)) {
				spilled.add(fileVariant);
				spilled.add(write(argument, command.get(++i), createdFiles).toString());
			}
			else {
				spilled.add(argument);
			}
		}

		// Anything still oversized has no file variant to fall back on — `--json-schema`
		// is the only realistic case. Say so now rather than letting exec fail obscurely.
		for (String argument : spilled) {
			if (oversized(argument, MAX_ARGUMENT_BYTES)) {
				throw new ClaudeCodeException(
						("A single command-line argument is %d bytes, over this system's %d-byte limit, "
								+ "and the Claude Code CLI has no file-based form of it. Shorten the value; "
								+ "note that the prompt itself is sent on stdin and is not subject to this limit.")
							.formatted(byteLength(argument), MAX_ARGUMENT_BYTES));
			}
		}
		return spilled;
	}

	/**
	 * Renders the argument sizes for an {@code E2BIG} diagnostic, largest first.
	 */
	static String describeSizes(List<String> command) {
		return command.stream()
			.filter(argument -> argument.startsWith("--") || byteLength(argument) > 1024)
			.map(argument -> "  %s -> %d bytes".formatted(abbreviate(argument), byteLength(argument)))
			.reduce((a, b) -> a + System.lineSeparator() + b)
			.orElse("  (no arguments)");
	}

	private static Path write(String flag, String value, List<Path> createdFiles) {
		try {
			// A system prompt may carry proprietary content: keep the file owner-only
			// where the filesystem supports it.
			Path file = createRestricted(flag);
			Files.writeString(file, value, StandardCharsets.UTF_8);
			createdFiles.add(file);
			return file;
		}
		catch (IOException ex) {
			throw new ClaudeCodeException(
					"Could not write the oversized `%s` value to a temporary file".formatted(flag), ex);
		}
	}

	private static Path createRestricted(String flag) throws IOException {
		String prefix = "claude-code-" + flag.replaceAll("[^a-zA-Z]+", "-");
		try {
			Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
			return Files.createTempFile(prefix, ".txt", PosixFilePermissions.asFileAttribute(ownerOnly));
		}
		catch (UnsupportedOperationException ex) {
			// Non-POSIX filesystem (Windows): fall back to the platform default.
			return Files.createTempFile(prefix, ".txt");
		}
	}

	private static boolean oversized(String argument, int threshold) {
		return byteLength(argument) >= threshold;
	}

	private static int byteLength(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private static String abbreviate(String argument) {
		return (argument.length() <= 40) ? argument : argument.substring(0, 40) + "…";
	}

}
