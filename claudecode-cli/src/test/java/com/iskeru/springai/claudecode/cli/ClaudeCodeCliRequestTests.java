package com.iskeru.springai.claudecode.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClaudeCodeCliRequestTests {

	@Test
	void unsetFieldsProduceNoFlags() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().prompt("hi").build();

		assertThat(request.toArguments()).isEmpty();
	}

	@Test
	void anEmptyToolListDisablesToolsRatherThanOmittingTheFlag() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().prompt("hi").tools(List.of()).build();

		assertThat(request.toArguments()).containsExactly("--tools", "");
	}

	@Test
	void toolsAreCommaSeparated() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder()
			.prompt("hi")
			.tools(List.of("Read", "Bash"))
			.build();

		assertThat(request.toArguments()).containsExactly("--tools", "Read,Bash");
	}

	@Test
	void aNullToolListLeavesTheCliDefaultInPlace() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().prompt("hi").tools(null).build();

		assertThat(request.toArguments()).doesNotContain("--tools");
	}

	@Test
	void everyModelledFieldRendersItsFlag() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder()
			.prompt("hi")
			.model("sonnet")
			.systemPrompt("Be terse.")
			.appendSystemPrompt("In English.")
			.tools(List.of())
			.effort("high")
			.fallbackModels(List.of("haiku", "opus"))
			.maxBudgetUsd(0.5)
			.jsonSchema("{\"type\":\"object\"}")
			.settingSources(List.of())
			.extraArgs(List.of("--verbose"))
			.build();

		assertThat(request.toArguments()).containsExactly("--model", "sonnet", "--system-prompt", "Be terse.",
				"--append-system-prompt", "In English.", "--tools", "", "--effort", "high", "--fallback-model",
				"haiku,opus", "--max-budget-usd", "0.5", "--json-schema", "{\"type\":\"object\"}", "--setting-sources",
				"", "--verbose");
	}

	@Test
	void thePromptIsNotAnArgument() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().prompt("a very long prompt").build();

		assertThat(request.toArguments()).as("the prompt goes to stdin, not argv").doesNotContain("a very long prompt");
	}

}
