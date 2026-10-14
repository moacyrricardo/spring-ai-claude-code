package com.iskeru.springai.claudecode;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.prompt.ChatOptions;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeChatOptionsTests {

	@Test
	void runtimeOptionsWinOverDefaults() {
		ClaudeCodeChatOptions defaults = ClaudeCodeChatOptions.builder().model("sonnet").effort("low").build();
		ClaudeCodeChatOptions runtime = ClaudeCodeChatOptions.builder().model("opus").build();

		ClaudeCodeChatOptions merged = ClaudeCodeChatOptions.merge(defaults, runtime);

		assertThat(merged.getModel()).isEqualTo("opus");
		assertThat(merged.getEffort()).as("unset runtime fields fall back to the default").isEqualTo("low");
	}

	@Test
	void aPlainChatOptionsContributesItsPortableFields() {
		ClaudeCodeChatOptions defaults = ClaudeCodeChatOptions.builder().model("sonnet").effort("high").build();
		ChatOptions runtime = ChatOptions.builder().model("haiku").build();

		ClaudeCodeChatOptions merged = ClaudeCodeChatOptions.merge(defaults, runtime);

		assertThat(merged.getModel()).isEqualTo("haiku");
		assertThat(merged.getEffort()).as("CLI-specific defaults survive a portable override").isEqualTo("high");
	}

	@Test
	void mergingWithoutAnOverrideReturnsACopyOfTheDefaults() {
		ClaudeCodeChatOptions defaults = ClaudeCodeChatOptions.builder().model("sonnet").tools(List.of("Read")).build();

		ClaudeCodeChatOptions merged = ClaudeCodeChatOptions.merge(defaults, null);
		merged.setModel("mutated");

		assertThat(defaults.getModel()).isEqualTo("sonnet");
		assertThat(merged.getTools()).containsExactly("Read");
	}

	@Test
	void mergeHandlesNullDefaults() {
		ClaudeCodeChatOptions merged = ClaudeCodeChatOptions.merge(null,
				ClaudeCodeChatOptions.builder().model("opus").build());

		assertThat(merged.getModel()).isEqualTo("opus");
	}

	@Test
	void anEmptyToolListOverridesAConfiguredOne() {
		ClaudeCodeChatOptions defaults = ClaudeCodeChatOptions.builder().tools(List.of("Read", "Bash")).build();
		ClaudeCodeChatOptions runtime = ClaudeCodeChatOptions.builder().tools(List.of()).build();

		assertThat(ClaudeCodeChatOptions.merge(defaults, runtime).getTools()).isEmpty();
	}

	@Test
	void mutateRoundTripsEveryField() {
		ClaudeCodeChatOptions original = ClaudeCodeChatOptions.builder()
			.model("sonnet")
			.systemPrompt("Be terse.")
			.appendSystemPrompt("Always answer in English.")
			.tools(List.of("Read"))
			.effort("high")
			.fallbackModels(List.of("haiku"))
			.maxBudgetUsd(0.25)
			.jsonSchema("{\"type\":\"object\"}")
			.settingSources(List.of("project"))
			.extraArgs(List.of("--verbose"))
			.temperature(0.7)
			.build();

		ClaudeCodeChatOptions roundTripped = (ClaudeCodeChatOptions) original.mutate().build();

		assertThat(roundTripped).usingRecursiveComparison().isEqualTo(original);
	}

	@Test
	void copyIsIndependentOfTheOriginal() {
		ClaudeCodeChatOptions original = ClaudeCodeChatOptions.builder().tools(List.of("Read")).build();

		ClaudeCodeChatOptions copy = original.copy();
		copy.getTools().add("Bash");

		assertThat(original.getTools()).containsExactly("Read");
	}

}
