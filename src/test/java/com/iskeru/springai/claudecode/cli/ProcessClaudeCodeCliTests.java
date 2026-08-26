package com.iskeru.springai.claudecode.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.iskeru.springai.claudecode.ClaudeCodeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the subprocess plumbing against a stub executable, so the real CLI is not
 * needed and no tokens are spent.
 */
@EnabledOnOs({ OS.LINUX, OS.MAC })
class ProcessClaudeCodeCliTests {

	@TempDir
	Path temp;

	private Path captureDir;

	@BeforeEach
	void setUp() throws IOException {
		this.captureDir = Files.createDirectories(this.temp.resolve("capture"));
	}

	@Test
	void passesThePromptOnStdinAndParsesTheResponse() throws Exception {
		Path stub = stubEchoing(RecordingFakeCli.envelope("PONG"));

		ClaudeCodeCliResponse response = cli(stub, Duration.ofSeconds(30))
			.execute(ClaudeCodeCliRequest.builder().prompt("What is 2 + 2?").build());

		assertThat(response.result()).isEqualTo("PONG");
		assertThat(Files.readString(this.captureDir.resolve("stdin.txt"))).isEqualTo("What is 2 + 2?");
	}

	@Test
	void alwaysRunsInPrintModeWithJsonOutput() throws Exception {
		Path stub = stubEchoing(RecordingFakeCli.envelope("ok"));

		cli(stub, Duration.ofSeconds(30)).execute(ClaudeCodeCliRequest.builder().prompt("hi").build());

		assertThat(capturedArguments()).containsSubsequence("--print", "--output-format", "json")
			.contains("--no-session-persistence");
	}

	@Test
	void appendsTheRequestsOwnFlags() throws Exception {
		Path stub = stubEchoing(RecordingFakeCli.envelope("ok"));

		cli(stub, Duration.ofSeconds(30))
			.execute(ClaudeCodeCliRequest.builder().prompt("hi").model("sonnet").tools(List.of()).build());

		assertThat(capturedArguments()).containsSubsequence("--model", "sonnet").containsSubsequence("--tools", "");
	}

	@Test
	void sessionPersistenceCanBeTurnedBackOn() throws Exception {
		Path stub = stubEchoing(RecordingFakeCli.envelope("ok"));

		ProcessClaudeCodeCli.builder()
			.executable(stub.toString())
			.timeout(Duration.ofSeconds(30))
			.environment(captureEnvironment())
			.sessionPersistence(true)
			.build()
			.execute(ClaudeCodeCliRequest.builder().prompt("hi").build());

		assertThat(capturedArguments()).doesNotContain("--no-session-persistence");
	}

	@Test
	void survivesAPromptLargerThanAPipeBuffer() throws Exception {
		Path stub = stubEchoing(RecordingFakeCli.envelope("ok"));
		// Comfortably past the 64 KiB Linux pipe buffer: a single-threaded writer would
		// block here forever waiting for a reader that is itself blocked on stdout.
		String hugePrompt = "x".repeat(512 * 1024);

		ClaudeCodeCliResponse response = cli(stub, Duration.ofSeconds(60))
			.execute(ClaudeCodeCliRequest.builder().prompt(hugePrompt).build());

		assertThat(response.result()).isEqualTo("ok");
		assertThat(Files.readString(this.captureDir.resolve("stdin.txt"))).hasSize(hugePrompt.length());
	}

	@Test
	void reportsANonZeroExitWithItsStderr() throws Exception {
		Path stub = stub("""
				echo 'Invalid API key' >&2
				exit 3
				""");

		assertThatThrownBy(() -> cli(stub, Duration.ofSeconds(30))
			.execute(ClaudeCodeCliRequest.builder().prompt("hi").build())).isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("exited with code 3")
			.hasMessageContaining("Invalid API key");
	}

	@Test
	void killsAndReportsAnInvocationThatOverrunsItsTimeout() throws Exception {
		Path stub = stub("sleep 30\n");

		assertThatThrownBy(() -> cli(stub, Duration.ofMillis(300))
			.execute(ClaudeCodeCliRequest.builder().prompt("hi").build())).isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("did not finish within");
	}

	@Test
	void surfacesAnErrorEnvelopeAsAnException() throws Exception {
		Path stub = stubEchoing("""
				{"result":"Overloaded","is_error":true,"api_error_status":"529"}""");

		assertThatThrownBy(() -> cli(stub, Duration.ofSeconds(30))
			.execute(ClaudeCodeCliRequest.builder().prompt("hi").build())).isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("529")
			.hasMessageContaining("Overloaded");
	}

	@Test
	void explainsAMissingExecutable() {
		ProcessClaudeCodeCli cli = ProcessClaudeCodeCli.builder()
			.executable(this.temp.resolve("definitely-not-installed").toString())
			.build();

		assertThatThrownBy(() -> cli.execute(ClaudeCodeCliRequest.builder().prompt("hi").build()))
			.isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("Is it installed and on PATH?");
	}

	@Test
	void writesAnOversizedSystemPromptToAFileTheCliCanRead() throws Exception {
		Path stub = stubCapturingTheSpilledSystemPrompt(RecordingFakeCli.envelope("ok"));
		String huge = "S".repeat(200 * 1024);

		ProcessClaudeCodeCli.builder()
			.executable(stub.toString())
			.timeout(Duration.ofSeconds(30))
			.environment(captureEnvironment())
			.build()
			.execute(ClaudeCodeCliRequest.builder().prompt("hi").systemPrompt(huge).build());

		List<String> arguments = capturedArguments();
		assertThat(arguments).contains("--system-prompt-file").doesNotContain("--system-prompt", huge);

		// The CLI must be handed the identical text, just by a different route. The stub
		// copies it out while the process is running, since the file is deleted after.
		assertThat(Files.readString(this.captureDir.resolve("system-prompt.txt"))).isEqualTo(huge);

		Path spilled = Path.of(arguments.get(arguments.indexOf("--system-prompt-file") + 1));
		assertThat(spilled).as("the temporary file is removed once the call completes").doesNotExist();
	}

	@Test
	void diagnosesAnOverlongCommandLineWithoutBlamingTheInstall() throws Exception {
		Path stub = stubEchoing(RecordingFakeCli.envelope("ok"));
		// Spilling disabled, so the oversized value really does reach exec().
		ProcessClaudeCodeCli cli = ProcessClaudeCodeCli.builder()
			.executable(stub.toString())
			.timeout(Duration.ofSeconds(30))
			.argumentSpillThreshold(Integer.MAX_VALUE)
			.environment(captureEnvironment())
			.build();

		assertThatThrownBy(() -> cli
			.execute(ClaudeCodeCliRequest.builder().prompt("hi").systemPrompt("S".repeat(300 * 1024)).build()))
			.isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("command line is too long")
			.hasMessageContaining("not because it is missing")
			.hasMessageContaining("sent on stdin")
			.as("the old message sent people to reinstall a CLI that was fine")
			.hasMessageNotContaining("Is it installed and on PATH?");
	}

	private ProcessClaudeCodeCli cli(Path executable, Duration timeout) {
		return ProcessClaudeCodeCli.builder()
			.executable(executable.toString())
			.timeout(timeout)
			.environment(captureEnvironment())
			.build();
	}

	private java.util.Map<String, String> captureEnvironment() {
		return java.util.Map.of("CAPTURE_DIR", this.captureDir.toString());
	}

	private List<String> capturedArguments() throws IOException {
		return Files.readAllLines(this.captureDir.resolve("args.txt"));
	}

	/** A stub that records its argv and stdin, then prints {@code payload} on stdout. */
	private Path stubEchoing(String payload) throws IOException {
		Path payloadFile = this.temp.resolve("payload.json");
		Files.writeString(payloadFile, payload, StandardCharsets.UTF_8);
		return stub("""
				for arg in "$@"; do printf '%%s\\n' "$arg"; done > "$CAPTURE_DIR/args.txt"
				cat > "$CAPTURE_DIR/stdin.txt"
				cat '%s'
				""".formatted(payloadFile));
	}

	/** As {@link #stubEchoing}, plus a copy of the spilled system prompt while it exists. */
	private Path stubCapturingTheSpilledSystemPrompt(String payload) throws IOException {
		Path payloadFile = this.temp.resolve("payload.json");
		Files.writeString(payloadFile, payload, StandardCharsets.UTF_8);
		return stub("""
				for arg in "$@"; do printf '%%s\\n' "$arg"; done > "$CAPTURE_DIR/args.txt"
				cat > "$CAPTURE_DIR/stdin.txt"
				prev=""
				for arg in "$@"; do
				  if [ "$prev" = "--system-prompt-file" ]; then cp "$arg" "$CAPTURE_DIR/system-prompt.txt"; fi
				  prev="$arg"
				done
				cat '%s'
				""".formatted(payloadFile));
	}

	private Path stub(String body) throws IOException {
		Path script = Files.createTempFile(this.temp, "claude-stub", ".sh");
		Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
		return script;
	}

}
