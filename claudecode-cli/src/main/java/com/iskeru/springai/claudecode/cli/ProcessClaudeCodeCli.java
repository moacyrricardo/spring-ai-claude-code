package com.iskeru.springai.claudecode.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

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

	public static final String MINIMUM_CLI_VERSION = "2.1.296";

	private static final Pattern VERSION = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)(?: \\(Claude Code\\))?$");

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

	private final int maxStdoutBytes;

	private final int maxStderrBytes;

	private volatile String verifiedVersion;

	protected ProcessClaudeCodeCli(Builder builder) {
		this.executable = builder.executable;
		this.timeout = builder.timeout;
		this.workingDirectory = builder.workingDirectory;
		this.environment = Map.copyOf(builder.environment);
		this.sessionPersistence = builder.sessionPersistence;
		this.objectMapper = builder.objectMapper;
		this.argumentSpillThreshold = builder.argumentSpillThreshold;
		this.maxStdoutBytes = builder.maxStdoutBytes;
		this.maxStderrBytes = builder.maxStderrBytes;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ClaudeCodeCliResponse execute(ClaudeCodeCliRequest request) {
		Objects.requireNonNull(request, "request");
		verifyVersion();
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
		ProcessRunner.Result result = run(command, request.prompt(), this.timeout, this.maxStdoutBytes);
		ClaudeCodeCliResponse response = ClaudeCodeCliResponse.fromJson(result.stdout(), this.objectMapper);
		if (response.error()) {
			throw new ClaudeCodeException("The Claude Code CLI reported an error (api_error_status=%s): %s"
				.formatted(response.apiErrorStatus(), response.result()));
		}
		return response;
	}

	/** Cached per executor; replay never calls this method. */
	public String verifyVersion() {
		if (this.verifiedVersion != null) {
			return this.verifiedVersion;
		}
		synchronized (this) {
			if (this.verifiedVersion != null) {
				return this.verifiedVersion;
			}
			Duration probeTimeout = this.timeout.compareTo(Duration.ofSeconds(10)) < 0 ? this.timeout : Duration.ofSeconds(10);
			String detected = run(List.of(this.executable, "--version"), "", probeTimeout, 4096).stdout().strip();
			var matcher = VERSION.matcher(detected);
			boolean supported = false;
			if (matcher.matches()) {
				try {
					int major = Integer.parseInt(matcher.group(1));
					int minor = Integer.parseInt(matcher.group(2));
					int patch = Integer.parseInt(matcher.group(3));
					supported = major > 2 || (major == 2 && (minor > 1 || (minor == 1 && patch >= 296)));
				}
				catch (NumberFormatException ignored) {
					// Treat an unrecognizable numeric version as unsupported.
				}
			}
			if (!supported) {
				throw new ClaudeCodeException("Unsupported Claude Code CLI version '" + detected + "'; upgrade to "
						+ MINIMUM_CLI_VERSION + " or newer. Isolation flags will not be dropped.");
			}
			this.verifiedVersion = detected;
			return detected;
		}
	}

	private ProcessRunner.Result run(List<String> command, String input, Duration limit, int stdoutLimit) {
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

		ProcessRunner.Result result = ProcessRunner.run(process, input, limit, stdoutLimit, this.maxStderrBytes);
		if (result.exitCode() != 0) {
			throw new ClaudeCodeException("The Claude Code CLI exited with code %d.%nCommand: %s%nstderr: %s%nstdout: %s"
				.formatted(result.exitCode(), String.join(" ", command), result.stderr().strip(), result.stdout().strip()));
		}
		return result;
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

	public static final class Builder {

		private String executable = "claude";

		private Duration timeout = DEFAULT_TIMEOUT;

		private Path workingDirectory;

		private Map<String, String> environment = new LinkedHashMap<>();

		private boolean sessionPersistence = false;

		private ObjectMapper objectMapper = new ObjectMapper();

		private int argumentSpillThreshold = DEFAULT_ARGUMENT_SPILL_THRESHOLD;

		private int maxStdoutBytes = 16 * 1024 * 1024;

		private int maxStderrBytes = 64 * 1024;

		public Builder maxStdoutBytes(int bytes) {
			this.maxStdoutBytes = bytes;
			return this;
		}

		public Builder maxStderrBytes(int bytes) {
			this.maxStderrBytes = bytes;
			return this;
		}

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
			if (this.executable == null || this.executable.isBlank()) {
				throw new IllegalArgumentException("executable must not be blank");
			}
			Objects.requireNonNull(this.objectMapper, "objectMapper");
			if (this.timeout == null || this.timeout.isNegative() || this.timeout.isZero()) {
				throw new IllegalArgumentException("timeout must be positive");
			}
			this.timeout.toNanos(); // Reject durations too large for monotonic deadline arithmetic.
			if (this.argumentSpillThreshold <= 0 || this.maxStdoutBytes <= 0 || this.maxStderrBytes <= 0) {
				throw new IllegalArgumentException("argumentSpillThreshold, maxStdoutBytes and maxStderrBytes must be positive");
			}
			return new ProcessClaudeCodeCli(this);
		}

	}

}
