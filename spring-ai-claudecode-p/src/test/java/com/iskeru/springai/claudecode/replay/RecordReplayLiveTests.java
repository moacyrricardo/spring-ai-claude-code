package com.iskeru.springai.claudecode.replay;

import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iskeru.springai.claudecode.ClaudeCodeChatModel;
import com.iskeru.springai.claudecode.ClaudeCodeChatOptions;
import com.iskeru.springai.claudecode.ClaudeCodeException;
import com.iskeru.springai.claudecode.cli.ProcessClaudeCodeCli;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Proves the whole point of the library end to end: record once against the real CLI, then
 * serve the same prompt from disk with no CLI reachable at all — which is what CI does.
 *
 * <p>
 * Needs Claude Code installed and authenticated. Run with {@code mvn test -Plive}.
 */
@Tag("live")
class RecordReplayLiveTests {

	@TempDir
	Path fixtures;

	private final ClaudeCodeChatOptions options = ClaudeCodeChatOptions.builder()
		.model("sonnet")
		.systemPrompt("Reply with the number only, no explanation.")
		.build();

	@Test
	void recordsAgainstTheRealCliThenReplaysOffline() {
		Prompt prompt = new Prompt("What is 6 * 7?");

		ChatResponse recorded = recordingModel().call(prompt);

		assertThat(recorded.getResult().getOutput().getText()).contains("42");
		assertThat(recorded.getResult().getMetadata().<Boolean>get("replayed")).isFalse();
		assertThat(this.fixtures).isNotEmptyDirectory();

		// This model has no delegate at all: if it answers, it answered from disk.
		ChatResponse replayed = offlineModel().call(prompt);

		assertThat(replayed.getResult().getOutput().getText())
			.isEqualTo(recorded.getResult().getOutput().getText());
		assertThat(replayed.getResult().getMetadata().<Boolean>get("replayed")).isTrue();
		assertThat(replayed.getMetadata().getModel()).isEqualTo(recorded.getMetadata().getModel());
		assertThat(replayed.getMetadata().getUsage().getTotalTokens())
			.as("a replayed response reports the original recording's usage")
			.isEqualTo(recorded.getMetadata().getUsage().getTotalTokens());
	}

	@Test
	void anUnrecordedPromptFailsInsteadOfSilentlyGoingLive() {
		recordingModel().call(new Prompt("What is 6 * 7?"));

		assertThatThrownBy(() -> offlineModel().call(new Prompt("A prompt that was never recorded.")))
			.isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("No recorded Claude Code fixture");
	}

	private ClaudeCodeChatModel recordingModel() {
		return ClaudeCodeChatModel.builder()
			.cli(ReplayingClaudeCodeCli.builder()
				.delegate(ProcessClaudeCodeCli.builder().build())
				.store(new FileSystemFixtureStore(this.fixtures))
				.mode(ReplayMode.AUTO)
				.build())
			.defaultOptions(this.options)
			.build();
	}

	private ClaudeCodeChatModel offlineModel() {
		return ClaudeCodeChatModel.builder()
			.cli(ReplayingClaudeCodeCli.builder()
				.store(new FileSystemFixtureStore(this.fixtures))
				.mode(ReplayMode.REPLAY)
				.build())
			.defaultOptions(this.options)
			.build();
	}

}
