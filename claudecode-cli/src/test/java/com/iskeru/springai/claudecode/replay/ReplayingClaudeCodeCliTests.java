package com.iskeru.springai.claudecode.replay;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iskeru.springai.claudecode.ClaudeCodeException;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCli;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;
import com.iskeru.springai.claudecode.cli.RecordingFakeCli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayingClaudeCodeCliTests {

	@TempDir
	Path fixtures;

	private final ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder()
		.prompt("What is 2 + 2?")
		.model("sonnet")
		.tools(List.of())
		.build();

	private FixtureStore store() {
		return new FileSystemFixtureStore(this.fixtures);
	}

	private ReplayingClaudeCodeCli cli(RecordingFakeCli delegate, ReplayMode mode) {
		return ReplayingClaudeCodeCli.builder().delegate(delegate).store(store()).mode(mode).build();
	}

	@Test
	void liveModeNeverTouchesTheFixtureStore() {
		RecordingFakeCli delegate = new RecordingFakeCli();

		cli(delegate, ReplayMode.LIVE).execute(this.request);
		cli(delegate, ReplayMode.LIVE).execute(this.request);

		assertThat(delegate.getInvocationCount()).isEqualTo(2);
		assertThat(this.fixtures).isEmptyDirectory();
	}

	@Test
	void recordModeCallsTheCliAndWritesAFixture() {
		RecordingFakeCli delegate = new RecordingFakeCli();

		ClaudeCodeCliResponse response = cli(delegate, ReplayMode.RECORD).execute(this.request);

		assertThat(response.result()).isEqualTo("ok");
		assertThat(response.replayed()).isFalse();
		assertThat(store().load(FixtureKey.of(this.request))).isPresent();
	}

	@Test
	void recordModeReRecordsEvenWhenAFixtureExists() {
		RecordingFakeCli first = new RecordingFakeCli(request -> RecordingFakeCli.envelope("stale"));
		cli(first, ReplayMode.RECORD).execute(this.request);

		RecordingFakeCli second = new RecordingFakeCli(request -> RecordingFakeCli.envelope("fresh"));
		ClaudeCodeCliResponse response = cli(second, ReplayMode.RECORD).execute(this.request);

		assertThat(response.result()).isEqualTo("fresh");
		assertThat(store().load(FixtureKey.of(this.request)).orElseThrow().result()).isEqualTo("fresh");
	}

	@Test
	void autoModeRecordsOnceThenReplays() {
		RecordingFakeCli delegate = new RecordingFakeCli(request -> RecordingFakeCli.envelope("4"));

		ClaudeCodeCliResponse recorded = cli(delegate, ReplayMode.AUTO).execute(this.request);
		ClaudeCodeCliResponse replayed = cli(delegate, ReplayMode.AUTO).execute(this.request);

		assertThat(delegate.getInvocationCount()).as("the second call is served from disk").isEqualTo(1);
		assertThat(recorded.replayed()).isFalse();
		assertThat(replayed.replayed()).isTrue();
		assertThat(replayed.result()).isEqualTo("4");
	}

	@Test
	void autoModeRecordsAgainWhenThePromptChanges() {
		RecordingFakeCli delegate = new RecordingFakeCli();

		cli(delegate, ReplayMode.AUTO).execute(this.request);
		cli(delegate, ReplayMode.AUTO)
			.execute(ClaudeCodeCliRequest.builder().prompt("What is 3 + 3?").model("sonnet").tools(List.of()).build());

		assertThat(delegate.getInvocationCount()).isEqualTo(2);
	}

	@Test
	void replayModeServesFromDiskWithoutCallingTheCli() {
		RecordingFakeCli recorder = new RecordingFakeCli(request -> RecordingFakeCli.envelope("4"));
		cli(recorder, ReplayMode.RECORD).execute(this.request);

		RecordingFakeCli neverCalled = new RecordingFakeCli();
		ClaudeCodeCliResponse response = cli(neverCalled, ReplayMode.REPLAY).execute(this.request);

		assertThat(neverCalled.getInvocationCount()).isZero();
		assertThat(response.result()).isEqualTo("4");
		assertThat(response.replayed()).isTrue();
	}

	@Test
	void replayModeNeedsNoCliAtAll() {
		ReplayingClaudeCodeCli recorder = ReplayingClaudeCodeCli.builder()
			.delegate(new RecordingFakeCli(request -> RecordingFakeCli.envelope("4")))
			.store(store())
			.mode(ReplayMode.RECORD)
			.build();
		recorder.execute(this.request);

		ClaudeCodeCli offline = ReplayingClaudeCodeCli.builder().store(store()).mode(ReplayMode.REPLAY).build();

		assertThat(offline.execute(this.request).result()).isEqualTo("4");
	}

	@Test
	void replayModeFailsLoudlyAndActionablyOnAMiss() {
		ClaudeCodeCli cli = cli(new RecordingFakeCli(), ReplayMode.REPLAY);

		assertThatThrownBy(() -> cli.execute(this.request)).isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("No recorded Claude Code fixture")
			.hasMessageContaining("What is 2 + 2?")
			.hasMessageContaining("spring.ai.claude-code.replay.mode")
			.hasMessageContaining(this.fixtures.toString());
	}

	@Test
	void aDelegateIsRequiredForEveryModeThatCanCallTheCli() {
		for (ReplayMode mode : List.of(ReplayMode.LIVE, ReplayMode.RECORD, ReplayMode.AUTO)) {
			assertThatThrownBy(() -> ReplayingClaudeCodeCli.builder().store(store()).mode(mode).build())
				.as("mode %s", mode)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("delegate is required");
		}
	}

	@Test
	void aStoreIsAlwaysRequired() {
		assertThatThrownBy(
				() -> ReplayingClaudeCodeCli.builder().delegate(new RecordingFakeCli()).mode(ReplayMode.AUTO).build())
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("FixtureStore");
	}

}
