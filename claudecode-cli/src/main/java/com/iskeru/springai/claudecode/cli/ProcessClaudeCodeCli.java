package com.iskeru.springai.claudecode.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.iskeru.springai.claudecode.ClaudeCodeException;

/**
 * Runs the real {@code claude} binary as a subprocess.
 *
 * <p>
 * Every invocation is independent: the prompt goes in on stdin, a JSON envelope comes back
 * on stdout, and the process exits. There is no session reuse, which is what makes the
 * responses cacheable by the record/replay layer.
 */
public class ProcessClaudeCodeCli implements ClaudeCodeCli {

	private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(5);

	/**
	 * How long to wait for the stdout/stderr pumps to finish after the process has exited.
	 * They are draining an already-closed pipe at that point, so this only guards against
	 * a pathological hang.
	 */
	private static final Duration PUMP_DRAIN_GRACE = Duration.ofSeconds(10);

	/**
	 * Arguments at or above this many bytes are written to a temporary file instead of
	 * being passed inline. Comfortably under the 128 KiB per-argument ceiling, leaving
	 * room for the rest of the command and the environment, which share a separate total.
	 */
	private static final int DEFAULT_ARGUMENT_SPILL_THRESHOLD = 64 * 1024;

	private final String executable;

	private final Duration timeout;

	private final Path workingDirectory;

	private final Map<String, String> environment;

	private final boolean sessionPersistence;

	private final ObjectMapper objectMapper;

	private final int argumentSpillThreshold;

	protected ProcessClaudeCodeCli(Builder builder) {
		this.executable = builder.executable;
		this.timeout = builder.timeout;
		this.workingDirectory = builder.workingDirectory;
		this.environment = Map.copyOf(builder.environment);
		this.sessionPersistence = builder.sessionPersistence;
		this.objectMapper = builder.objectMapper;
		this.argumentSpillThreshold = builder.argumentSpillThreshold;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ClaudeCodeCliResponse execute(ClaudeCodeCliRequest request) {
		List<Path> spillFiles = new ArrayList<>();
		try {
			return execute(request, spillFiles);
		}
		finally {
			for (Path file : spillFiles) {
				try {
					Files.deleteIfExists(file);
				}
				catch (IOException ex) {
					// A leftover temp file is not worth failing a completed call over.
				}
			}
		}
	}

	private ClaudeCodeCliResponse execute(ClaudeCodeCliRequest request, List<Path> spillFiles) {
		List<String> command = ArgumentSpill.apply(buildCommand(request), this.argumentSpillThreshold, spillFiles);

		ProcessBuilder processBuilder = new ProcessBuilder(command);
		if (this.workingDirectory != null) {
			processBuilder.directory(this.workingDirectory.toFile());
		}
		processBuilder.environment().putAll(this.environment);

		Process process;
		try {
			process = processBuilder.start();
		}
		catch (IOException ex) {
			throw new ClaudeCodeException(startFailureMessage(command, ex), ex);
		}

		AtomicReference<String> stdout = new AtomicReference<>("");
		AtomicReference<String> stderr = new AtomicReference<>("");
		AtomicReference<IOException> stdinFailure = new AtomicReference<>();

		// All three streams are pumped concurrently: a large prompt can fill the stdin
		// pipe buffer before the CLI starts reading, and a chatty stderr can fill its
		// buffer before we get to stdout — either would deadlock a single-threaded read.
		Thread stdinPump = daemon("claude-cli-stdin", () -> {
			try (OutputStream in = process.getOutputStream()) {
				in.write(request.prompt().getBytes(StandardCharsets.UTF_8));
			}
			catch (IOException ex) {
				stdinFailure.set(ex);
			}
		});
		Thread stdoutPump = daemon("claude-cli-stdout", () -> stdout.set(drain(process.getInputStream())));
		Thread stderrPump = daemon("claude-cli-stderr", () -> stderr.set(drain(process.getErrorStream())));

		boolean exited;
		try {
			exited = process.waitFor(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
			if (!exited) {
				process.destroyForcibly();
				throw new ClaudeCodeException("The Claude Code CLI did not finish within %s. Command: %s"
					.formatted(this.timeout, String.join(" ", command)));
			}
			// The pipes are closed now; these joins just collect what is already buffered.
			stdoutPump.join(PUMP_DRAIN_GRACE.toMillis());
			stderrPump.join(PUMP_DRAIN_GRACE.toMillis());
			stdinPump.join(PUMP_DRAIN_GRACE.toMillis());
		}
		catch (InterruptedException ex) {
			process.destroyForcibly();
			Thread.currentThread().interrupt();
			throw new ClaudeCodeException("Interrupted while waiting for the Claude Code CLI", ex);
		}

		if (stdinFailure.get() != null && stdout.get().isBlank()) {
			throw new ClaudeCodeException("Failed to write the prompt to the Claude Code CLI's stdin",
					stdinFailure.get());
		}

		int exitCode = process.exitValue();
		if (exitCode != 0) {
			throw new ClaudeCodeException("The Claude Code CLI exited with code %d.%nCommand: %s%nstderr: %s%nstdout: %s"
				.formatted(exitCode, String.join(" ", command), stderr.get().strip(), stdout.get().strip()));
		}

		ClaudeCodeCliResponse response = ClaudeCodeCliResponse.fromJson(stdout.get(), this.objectMapper);
		if (response.error()) {
			throw new ClaudeCodeException("The Claude Code CLI reported an error (api_error_status=%s): %s"
				.formatted(response.apiErrorStatus(), response.result()));
		}
		return response;
	}

	/**
	 * Distinguishes "the binary is missing" from "the kernel refused this command line".
	 * They arrive as the same {@link IOException}, and blaming the install for an
	 * oversized argument sends people to reinstall a CLI that was never the problem.
	 */
	private String startFailureMessage(List<String> command, IOException cause) {
		String detail = String.valueOf(cause.getMessage());
		if (detail.contains("Argument list too long") || detail.contains("error=7")) {
			return ("The Claude Code CLI could not be started because the command line is too long for this "
					+ "operating system, not because it is missing.%nArgument sizes:%n%s%n"
					+ "The prompt itself is sent on stdin and is never the cause. Shorten the offending value, "
					+ "or lower ProcessClaudeCodeCli.Builder#argumentSpillThreshold so it is written to a file.")
				.formatted(ArgumentSpill.describeSizes(command));
		}
		return "Could not start the Claude Code CLI (`%s`). Is it installed and on PATH?".formatted(this.executable);
	}

	protected List<String> buildCommand(ClaudeCodeCliRequest request) {
		List<String> command = new ArrayList<>();
		command.add(this.executable);
		command.add("--print");
		command.add("--output-format");
		command.add("json");
		if (!this.sessionPersistence) {
			command.add("--no-session-persistence");
		}
		command.addAll(request.toArguments());
		return command;
	}

	private static Thread daemon(String name, Runnable body) {
		Thread thread = new Thread(body, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	private static String drain(InputStream stream) {
		try (stream) {
			return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			return "";
		}
	}

	public static final class Builder {

		private String executable = "claude";

		private Duration timeout = DEFAULT_TIMEOUT;

		private Path workingDirectory;

		private Map<String, String> environment = new LinkedHashMap<>();

		private boolean sessionPersistence = false;

		private ObjectMapper objectMapper = new ObjectMapper();

		private int argumentSpillThreshold = DEFAULT_ARGUMENT_SPILL_THRESHOLD;

		/**
		 * Path to (or name of) the {@code claude} binary. Defaults to {@code claude},
		 * resolved on {@code PATH}.
		 */
		public Builder executable(String executable) {
			this.executable = executable;
			return this;
		}

		/**
		 * Byte size at which a system-prompt argument is written to a temporary file
		 * rather than passed inline, to stay under the operating system's per-argument
		 * limit. Defaults to 64 KiB. Spilling is invisible to the CLI and to fixture
		 * keys.
		 */
		public Builder argumentSpillThreshold(int argumentSpillThreshold) {
			this.argumentSpillThreshold = argumentSpillThreshold;
			return this;
		}

		/** How long a single invocation may take. Defaults to 5 minutes. */
		public Builder timeout(Duration timeout) {
			this.timeout = timeout;
			return this;
		}

		/**
		 * Directory the CLI runs in. Relevant only if you re-enable tools or project
		 * settings; a pure text completion does not care.
		 */
		public Builder workingDirectory(Path workingDirectory) {
			this.workingDirectory = workingDirectory;
			return this;
		}

		/** Extra environment variables, merged over the inherited environment. */
		public Builder environment(Map<String, String> environment) {
			this.environment = new LinkedHashMap<>(environment);
			return this;
		}

		/**
		 * Whether to let the CLI persist sessions to disk. Off by default so that test
		 * runs do not accumulate session files.
		 */
		public Builder sessionPersistence(boolean sessionPersistence) {
			this.sessionPersistence = sessionPersistence;
			return this;
		}

		public Builder objectMapper(ObjectMapper objectMapper) {
			this.objectMapper = objectMapper;
			return this;
		}

		public ProcessClaudeCodeCli build() {
			return new ProcessClaudeCodeCli(this);
		}

	}

}
