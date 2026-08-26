package com.iskeru.springai.claudecode;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultConversationRendererTests {

	private final DefaultConversationRenderer renderer = new DefaultConversationRenderer();

	@Test
	void singleUserMessageIsPassedThroughVerbatim() {
		ConversationRenderer.RenderedPrompt rendered = this.renderer.render(List.of(new UserMessage("What is 2 + 2?")));

		assertThat(rendered.systemPrompt()).isNull();
		assertThat(rendered.userPrompt()).isEqualTo("What is 2 + 2?");
	}

	@Test
	void systemMessagesAreLiftedOutOfTheUserPrompt() {
		ConversationRenderer.RenderedPrompt rendered = this.renderer
			.render(List.of(new SystemMessage("Be terse."), new UserMessage("Hello")));

		assertThat(rendered.systemPrompt()).isEqualTo("Be terse.");
		assertThat(rendered.userPrompt()).isEqualTo("Hello");
	}

	@Test
	void multipleSystemMessagesAreJoined() {
		ConversationRenderer.RenderedPrompt rendered = this.renderer
			.render(List.of(new SystemMessage("Be terse."), new SystemMessage("Answer in English."),
					new UserMessage("Hello")));

		assertThat(rendered.systemPrompt()).isEqualTo("Be terse.\n\nAnswer in English.");
	}

	@Test
	void blankSystemMessagesAreIgnored() {
		ConversationRenderer.RenderedPrompt rendered = this.renderer
			.render(List.of(new SystemMessage("   "), new UserMessage("Hello")));

		assertThat(rendered.systemPrompt()).isNull();
	}

	@Test
	void priorTurnsAreRenderedAsATaggedTranscript() {
		ConversationRenderer.RenderedPrompt rendered = this.renderer.render(List.of(new UserMessage("What is 2 + 2?"),
				new AssistantMessage("4."), new UserMessage("And times 3?")));

		assertThat(rendered.userPrompt()).contains("<conversation>")
			.contains("<user>What is 2 + 2?</user>")
			.contains("<assistant>4.</assistant>")
			.contains("<user>And times 3?</user>")
			.contains("</conversation>");
	}

	@Test
	void aPromptWithoutAUserMessageIsRejected() {
		assertThatThrownBy(() -> this.renderer.render(List.of(new SystemMessage("Be terse."))))
			.isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("no user message");
	}

}
