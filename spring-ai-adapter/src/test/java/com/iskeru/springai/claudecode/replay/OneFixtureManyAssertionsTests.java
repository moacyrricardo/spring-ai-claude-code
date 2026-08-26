package com.iskeru.springai.claudecode.replay;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iskeru.springai.claudecode.ClaudeCodeChatModel;
import com.iskeru.springai.claudecode.ClaudeCodeChatOptions;
import com.iskeru.springai.claudecode.cli.RecordingFakeCli;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A prompt is usually worth more than one assertion: one test checks the text, another the
 * usage accounting, another the finish reason. Because a fixture is keyed by the request
 * rather than by the caller, all of them share a single recording — the model is invoked
 * once and every later assertion is served from disk, even within one suite run.
 *
 * <p>
 * This is what makes replay pay off immediately rather than only on the second CI run.
 */
class OneFixtureManyAssertionsTests {

	@TempDir
	Path fixtures;

	private static final String PROMPT = "Summarise the release notes.";

	@Test
	void severalAssertionsOnTheSamePromptCostOneModelCall() {
		RecordingFakeCli cli = new RecordingFakeCli();
		ClaudeCodeChatModel model = model(cli);

		// Three separate assertions, exactly as three tests in a suite would make them.
		ChatResponse text = model.call(new Prompt(PROMPT));
		assertThat(text.getResult().getOutput().getText()).isNotBlank();

		ChatResponse usage = model.call(new Prompt(PROMPT));
		assertThat(usage.getMetadata().getUsage().getTotalTokens()).isPositive();

		ChatResponse finishReason = model.call(new Prompt(PROMPT));
		assertThat(finishReason.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");

		assertThat(cli.getInvocationCount()).as("only the first call reaches the CLI; the rest replay").isEqualTo(1);
		assertThat(this.fixtures).isDirectoryContaining(path -> path.getFileName().toString().endsWith(".json"));
	}

	@Test
	void everyAssertionSeesIdenticalContent() {
		ClaudeCodeChatModel model = model(new RecordingFakeCli());

		ChatResponse recorded = model.call(new Prompt(PROMPT));
		ChatResponse replayed = model.call(new Prompt(PROMPT));

		assertThat(replayed.getResult().getOutput().getText()).isEqualTo(recorded.getResult().getOutput().getText());
		assertThat(replayed.getMetadata().getUsage().getTotalTokens())
			.isEqualTo(recorded.getMetadata().getUsage().getTotalTokens());
		assertThat(replayed.getResult().getMetadata().<Boolean>get("replayed"))
			.as("a replayed response says so, so a test can prove it never went live")
			.isTrue();
	}

	@Test
	void aDifferentPromptIsADifferentFixture() {
		RecordingFakeCli cli = new RecordingFakeCli();
		ClaudeCodeChatModel model = model(cli);

		model.call(new Prompt(PROMPT));
		model.call(new Prompt("A different question entirely."));
		model.call(new Prompt(PROMPT));

		assertThat(cli.getInvocationCount()).as("sharing is by request identity, not by call count").isEqualTo(2);
	}

	private ClaudeCodeChatModel model(RecordingFakeCli cli) {
		return ClaudeCodeChatModel.builder()
			.cli(ReplayingClaudeCodeCli.builder()
				.delegate(cli)
				.store(new FileSystemFixtureStore(this.fixtures))
				.mode(ReplayMode.AUTO)
				.build())
			.defaultOptions(ClaudeCodeChatOptions.builder().model("sonnet").tools(List.of()).build())
			.build();
	}

}
