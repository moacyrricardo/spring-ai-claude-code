package com.iskeru.springai.claudecode.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.iskeru.springai.claudecode.ClaudeCodeException;
import com.iskeru.springai.claudecode.replay.FileSystemFixtureStore;
import com.iskeru.springai.claudecode.replay.FixtureKey;
import com.iskeru.springai.claudecode.replay.ReplayMode;
import com.iskeru.springai.claudecode.replay.ReplayingClaudeCodeCli;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledOnOs({ OS.LINUX, OS.MAC })
@Timeout(20)
class ProcessReliabilityTests {

	@TempDir
	Path temp;

	private final ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().prompt("hi").build();

	@Test
	void verifiesTheVersionOnlyOncePerExecutor() throws Exception {
		Path count = this.temp.resolve("probes");
		Path stub = raw("if [ \"$1\" = --version ]; then echo probe >> '" + count
				+ "'; echo '2.1.296 (Claude Code)'; exit 0; fi\ncat >/dev/null\necho '{\"result\":\"ok\"}'\n");
		var cli = ProcessClaudeCodeCli.builder().executable(stub.toString()).build();
		assertThat(cli.execute(this.request).result()).isEqualTo("ok");
		assertThat(cli.execute(this.request).result()).isEqualTo("ok");
		assertThat(Files.readAllLines(count)).containsExactly("probe");
	}

	@Test
	void rejectsOldAndUnknownVersionsWithoutSendingAPrompt() throws Exception {
		Path called = this.temp.resolve("called");
		for (String version : List.of("2.1.295", "2.0.999", "1.99.999", "unknown", "2.1.296-beta")) {
			Path stub = raw("if [ \"$1\" = --version ]; then echo '" + version + "'; exit 0; fi\ntouch '" + called + "'\n");
			assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().executable(stub.toString()).build().execute(this.request))
				.hasMessageContaining(version).hasMessageContaining("upgrade to 2.1.296");
		}
		assertThat(called).doesNotExist();
	}

	@Test
	void acceptsNewerSemanticVersions() throws Exception {
		for (String version : List.of("2.1.297", "2.2.0", "3.0.0")) {
			Path stub = raw("echo '" + version + "'\n");
			assertThat(ProcessClaudeCodeCli.builder().executable(stub.toString()).build().verifyVersion()).isEqualTo(version);
		}
	}

	@Test
	void versionProbeIsBounded() throws Exception {
		Path stub = raw("sleep 30\n");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().executable(stub.toString())
			.timeout(Duration.ofMillis(200)).build().execute(this.request)).hasMessageContaining("did not finish within");
	}

	@Test
	void replayDoesNotProbeEvenWithAProcessDelegate() {
		var store = new FileSystemFixtureStore(this.temp);
		store.save(FixtureKey.of(this.request), this.request,
				ClaudeCodeCliResponse.fromJson("{\"result\":\"offline\"}", new ObjectMapper()));
		var cli = ReplayingClaudeCodeCli.builder().store(store).mode(ReplayMode.REPLAY)
			.delegate(ProcessClaudeCodeCli.builder().executable("/does-not-exist/claude").build()).build();
		assertThat(cli.execute(this.request).result()).isEqualTo("offline");
	}

	@Test
	void timeoutTerminatesTheParentAndChildAndFinishesPumps() throws Exception {
		Path stub = treeStub();
		try {
			assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().executable(stub.toString())
				.timeout(Duration.ofSeconds(1)).build().execute(this.request)).hasMessageContaining("did not finish within");
			assertTreeExited();
			assertNoPumps();
		}
		finally { killTree(); }
	}

	@Test
	void interruptionCleansUpAndPreservesTheInterruptFlag() throws Exception {
		Path stub = treeStub();
		var cli = ProcessClaudeCodeCli.builder().executable(stub.toString()).build();
		var failure = new AtomicReference<Throwable>();
		var interrupted = new AtomicBoolean();
		Thread caller = new Thread(() -> {
			try { cli.execute(this.request); }
			catch (Throwable ex) { failure.set(ex); }
			finally { interrupted.set(Thread.currentThread().isInterrupted()); }
		});
		caller.start();
		try {
			awaitFile(this.temp.resolve("pids"));
			caller.interrupt();
			caller.join(7000);
			assertThat(caller.isAlive()).isFalse();
			assertThat(failure.get()).isInstanceOf(ClaudeCodeException.class).hasMessageContaining("Interrupted");
			assertThat(interrupted).isTrue();
			assertTreeExited();
			assertNoPumps();
		}
		finally { caller.interrupt(); killTree(); caller.join(7000); }
	}

	@Test
	void outputOverflowFailsInsteadOfReturningATruncatedAnswer() throws Exception {
		Path stub = versioned("cat >/dev/null\nhead -c 4096 /dev/zero\nsleep 30\n");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().executable(stub.toString()).maxStdoutBytes(1024)
			.timeout(Duration.ofSeconds(3)).build().execute(this.request)).hasMessageContaining("stdout exceeds");
		assertNoPumps();
	}

	@Test
	void brokenStdinFailsPromptlyAndCleansUpTheStillRunningProcess() throws Exception {
		Path stub = versioned("exec 0<&-\nsleep 30\n");
		var large = ClaudeCodeCliRequest.builder().prompt("x".repeat(512 * 1024)).build();
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().executable(stub.toString())
			.timeout(Duration.ofSeconds(5)).build().execute(large)).hasMessageContaining("Failed to write");
		assertNoPumps();
	}

	@Test
	void stderrRetainsOnlyItsTailAndMarksTruncation() throws Exception {
		Path stub = versioned("cat >/dev/null\nhead -c 4096 /dev/zero >&2\necho LAST-DIAGNOSTIC >&2\nexit 7\n");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().executable(stub.toString()).maxStderrBytes(64)
			.build().execute(this.request)).hasMessageContaining("exited with code 7")
			.hasMessageContaining("stderr truncated").hasMessageContaining("LAST-DIAGNOSTIC");
	}

	@Test
	void stdoutAtTheExactByteLimitIsAccepted() throws Exception {
		String json = "{\"result\":\"ok\"}";
		Path stub = versioned("cat >/dev/null\nprintf '%s' '" + json + "'\n");
		assertThat(ProcessClaudeCodeCli.builder().executable(stub.toString())
			.maxStdoutBytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length).build()
			.execute(this.request).result()).isEqualTo("ok");
	}

	@Test
	void pumpReadFailureIsReportedWithItsCause() {
		IOException cause = new IOException("broken stdout");
		Process process = new Process() {
			public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
			public InputStream getInputStream() { return new InputStream() {
				public int read() throws IOException { throw cause; }
			}; }
			public InputStream getErrorStream() { return InputStream.nullInputStream(); }
			public int waitFor() { return 0; }
			public int exitValue() { return 0; }
			public void destroy() { }
			public Stream<ProcessHandle> descendants() { return Stream.empty(); }
		};
		assertThatThrownBy(() -> ProcessRunner.run(process, "hi", Duration.ofSeconds(1), 128, 128))
			.hasCause(cause).hasMessageContaining("broken stdout");
		assertNoPumps();
	}

	@Test
	void validatesBuilderInputsBeforeStartingAnything() {
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().executable(" ").build()).hasMessageContaining("executable");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().objectMapper(null).build()).hasMessageContaining("objectMapper");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().timeout(Duration.ZERO).build()).hasMessageContaining("timeout");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().timeout(Duration.ofMillis(-1)).build()).hasMessageContaining("timeout");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().maxStdoutBytes(0).build()).hasMessageContaining("positive");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().maxStderrBytes(-1).build()).hasMessageContaining("positive");
		assertThatThrownBy(() -> ProcessClaudeCodeCli.builder().argumentSpillThreshold(0).build()).hasMessageContaining("positive");
	}

	private Path treeStub() throws IOException {
		return versioned("sleep 60 &\nchild=$!\nprintf '%s\\n%s\\n' $$ $child > '" + this.temp.resolve("pids.tmp")
				+ "'\nmv '" + this.temp.resolve("pids.tmp") + "' '" + this.temp.resolve("pids") + "'\nwait\n");
	}

	private void assertTreeExited() throws IOException {
		for (String pid : Files.readAllLines(this.temp.resolve("pids"))) {
			assertThat(ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false)).as("PID %s", pid).isFalse();
		}
	}

	private void killTree() throws IOException {
		Path pids = this.temp.resolve("pids");
		if (Files.exists(pids)) {
			var lines = Files.readAllLines(pids);
			for (int i = lines.size() - 1; i >= 0; i--) ProcessHandle.of(Long.parseLong(lines.get(i))).ifPresent(ProcessHandle::destroyForcibly);
		}
	}

	private static void awaitFile(Path file) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (!Files.exists(file) && System.nanoTime() < deadline) Thread.sleep(10);
		assertThat(file).exists();
	}

	private static void assertNoPumps() {
		assertThat(Thread.getAllStackTraces().keySet()).noneMatch(t -> t.isAlive() && t.getName().startsWith("claude-cli-"));
	}

	private Path versioned(String body) throws IOException {
		return raw("if [ \"$1\" = --version ]; then echo '2.1.296 (Claude Code)'; exit 0; fi\n" + body);
	}

	private Path raw(String body) throws IOException {
		Path path = Files.createTempFile(this.temp, "stub", ".sh");
		Files.writeString(path, "#!/bin/sh\n" + body);
		assertThat(path.toFile().setExecutable(true)).isTrue();
		return path;
	}

}
