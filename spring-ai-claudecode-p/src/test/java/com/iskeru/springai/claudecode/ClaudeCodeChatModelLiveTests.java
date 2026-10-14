package com.iskeru.springai.claudecode;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.iskeru.springai.claudecode.cli.ProcessClaudeCodeCli;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real {@code claude} CLI end to end.
 *
 * <p>
 * Excluded from the default build because it needs Claude Code installed and
 * authenticated, and it consumes subscription usage. Run it deliberately:
 *
 * <pre>{@code mvn test -Plive}</pre>
 */
@Tag("live")
class ClaudeCodeChatModelLiveTests {

	private final ClaudeCodeChatModel model = ClaudeCodeChatModel.builder()
		.cli(ProcessClaudeCodeCli.builder().timeout(Duration.ofMinutes(2)).build())
		.defaultOptions(ClaudeCodeChatOptions.builder().model("sonnet").build())
		.build();

	@Test
	void answersASimpleQuestion() {
		ChatResponse response = this.model.call(new Prompt(
				List.of(new SystemMessage("You are a calculator. Reply with the number only, no explanation."),
						new UserMessage("What is 2 + 2?"))));

		assertThat(response.getResult().getOutput().getText().strip()).isEqualTo("4");
	}

	@Test
	void reportsUsageAndTheServingModel() {
		ChatResponse response = this.model.call(new Prompt("Reply with exactly: PONG"));

		assertThat(response.getMetadata().getModel()).startsWith("claude-");
		assertThat(response.getMetadata().getId()).isNotBlank();
		assertThat(response.getMetadata().getUsage().getCompletionTokens()).isPositive();
		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
	}

	@Test
	void carriesPriorTurnsIntoTheAnswer() {
		ChatResponse response = this.model.call(new Prompt(List.of(new SystemMessage("Reply with the number only."),
				new UserMessage("Remember the number 7."), new AssistantMessage("7"),
				new UserMessage("What number did I ask you to remember?"))));

		assertThat(response.getResult().getOutput().getText()).contains("7");
	}

}
