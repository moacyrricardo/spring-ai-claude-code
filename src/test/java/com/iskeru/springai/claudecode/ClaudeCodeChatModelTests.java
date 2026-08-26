package com.iskeru.springai.claudecode;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;
import com.iskeru.springai.claudecode.cli.RecordingFakeCli;

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeChatModelTests {

	private final RecordingFakeCli cli = new RecordingFakeCli();

	private ClaudeCodeChatModel model(ClaudeCodeChatOptions defaults) {
		ClaudeCodeChatModel.Builder builder = ClaudeCodeChatModel.builder().cli(this.cli);
		if (defaults != null) {
			builder.defaultOptions(defaults);
		}
		return builder.build();
	}

	@Test
	void returnsTheCliResultAsTheAssistantMessage() {
		assertThat(model(null).call("What is 2 + 2?")).isEqualTo("ok");
	}

	@Test
	void disablesToolsAndSettingsByDefault() {
		model(null).call(new Prompt("hi"));

		ClaudeCodeCliRequest request = this.cli.getOnlyRequest();
		assertThat(request.tools()).as("a ChatModel should answer, not run tools").isEmpty();
		assertThat(request.settingSources()).as("answers must not depend on local Claude Code config").isEmpty();
	}

	@Test
	void substitutesAPlainAssistantSystemPromptForTheAgentOne() {
		model(null).call(new Prompt("hi"));

		assertThat(this.cli.getOnlyRequest().systemPrompt()).isEqualTo(ClaudeCodeChatModel.DEFAULT_SYSTEM_PROMPT);
	}

	@Test
	void aSystemMessageBeatsTheConfiguredSystemPrompt() {
		model(ClaudeCodeChatOptions.builder().systemPrompt("From configuration").build())
			.call(new Prompt(List.of(new SystemMessage("From the prompt"), new UserMessage("hi"))));

		assertThat(this.cli.getOnlyRequest().systemPrompt()).isEqualTo("From the prompt");
	}

	@Test
	void theConfiguredSystemPromptAppliesWhenThePromptHasNone() {
		model(ClaudeCodeChatOptions.builder().systemPrompt("From configuration").build()).call(new Prompt("hi"));

		assertThat(this.cli.getOnlyRequest().systemPrompt()).isEqualTo("From configuration");
	}

	@Test
	void runtimeOptionsOverrideTheDefaults() {
		model(ClaudeCodeChatOptions.builder().model("sonnet").effort("low").build())
			.call(new Prompt("hi", ClaudeCodeChatOptions.builder().model("opus").build()));

		ClaudeCodeCliRequest request = this.cli.getOnlyRequest();
		assertThat(request.model()).isEqualTo("opus");
		assertThat(request.effort()).isEqualTo("low");
	}

	@Test
	void portableChatOptionsFromTheChatClientAreHonoured() {
		model(ClaudeCodeChatOptions.builder().model("sonnet").build())
			.call(new Prompt("hi", ChatOptions.builder().model("haiku").build()));

		assertThat(this.cli.getOnlyRequest().model()).isEqualTo("haiku");
	}

	@Test
	void toolsCanBeReEnabledExplicitly() {
		model(ClaudeCodeChatOptions.builder().tools(List.of("Read", "Grep")).build()).call(new Prompt("hi"));

		assertThat(this.cli.getOnlyRequest().tools()).containsExactly("Read", "Grep");
	}

	@Test
	void mapsUsageWithCacheTokensFoldedIntoThePromptCount() {
		ChatResponse response = model(null).call(new Prompt("hi"));

		Usage usage = response.getMetadata().getUsage();
		assertThat(usage.getPromptTokens()).as("185 uncached + 3289 cache read + 7933 cache write").isEqualTo(11407);
		assertThat(usage.getCompletionTokens()).isEqualTo(5);
		assertThat(usage.getTotalTokens()).isEqualTo(11412);
		assertThat(usage.getCacheReadInputTokens()).isEqualTo(3289L);
		assertThat(usage.getCacheWriteInputTokens()).isEqualTo(7933L);
	}

	@Test
	void exposesTheCliSessionIdAndModelAsResponseMetadata() {
		ChatResponse response = model(null).call(new Prompt("hi"));

		assertThat(response.getMetadata().getId()).isEqualTo("11111111-2222-4333-8444-555555555555");
		assertThat(response.getMetadata().getModel()).isEqualTo("claude-sonnet-5");
		assertThat(response.getMetadata().<Double>get("totalCostUsd")).isEqualTo(0.0486657);
	}

	@Test
	void exposesTheStopReasonAsTheFinishReason() {
		ChatResponse response = model(null).call(new Prompt("hi"));

		assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
		assertThat(response.getResult().getMetadata().<Boolean>get("replayed")).isFalse();
	}

	@Test
	void streamEmitsTheWholeAnswerAsASingleElement() {
		List<ChatResponse> chunks = model(null).stream(new Prompt("hi")).collectList().block();

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).getResult().getOutput().getText()).isEqualTo("ok");
	}

	@Test
	void streamIsLazyUntilSubscribed() {
		model(null).stream(new Prompt("hi"));

		assertThat(this.cli.getInvocationCount()).isZero();
	}

	@Test
	void getOptionsReturnsAnIndependentCopyOfTheDefaults() {
		ClaudeCodeChatOptions defaults = ClaudeCodeChatOptions.builder().model("sonnet").build();
		ChatOptions exposed = model(defaults).getOptions();

		((ClaudeCodeChatOptions) exposed).setModel("mutated");

		assertThat(defaults.getModel()).isEqualTo("sonnet");
	}

}
