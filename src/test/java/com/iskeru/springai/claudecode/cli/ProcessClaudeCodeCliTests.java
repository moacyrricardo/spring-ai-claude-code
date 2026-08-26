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

	private Path stub(String body) throws IOException {
		Path script = Files.createTempFile(this.temp, "claude-stub", ".sh");
		Files.writeString(script, "#!/bin/sh\n" + body, StandardCharsets.UTF_8);
		Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
		return script;
	}

}
