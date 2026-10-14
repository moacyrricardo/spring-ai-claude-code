package com.iskeru.springai.claudecode.cli;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCodeCliRequestTests {

	@Test
	void customUnsetFieldsProduceNoFlags() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().isolation(IsolationPolicy.CUSTOM).prompt("hi").build();

		assertThat(request.toArguments()).isEmpty();
	}

	@Test
	void anEmptyToolListDisablesToolsRatherThanOmittingTheFlag() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().isolation(IsolationPolicy.CUSTOM).prompt("hi").tools(List.of()).build();

		assertThat(request.toArguments()).containsExactly("--tools", "");
	}

	@Test
	void toolsAreCommaSeparated() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder()
			.isolation(IsolationPolicy.CUSTOM)
			.prompt("hi")
			.tools(List.of("Read", "Bash"))
			.build();

		assertThat(request.toArguments()).containsExactly("--tools", "Read,Bash");
	}

	@Test
	void aNullToolListLeavesTheCliDefaultInPlace() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().isolation(IsolationPolicy.CUSTOM).prompt("hi").tools(null).build();

		assertThat(request.toArguments()).doesNotContain("--tools");
	}

	@Test
	void everyModelledFieldRendersItsFlag() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder()
			.isolation(IsolationPolicy.CUSTOM)
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
			.extraArgs(List.of("--max-turns", "2"))
			.build();

		assertThat(request.toArguments()).containsExactly("--model", "sonnet", "--system-prompt", "Be terse.",
				"--append-system-prompt", "In English.", "--tools", "", "--effort", "high", "--fallback-model",
				"haiku,opus", "--max-budget-usd", "0.5", "--json-schema", "{\"type\":\"object\"}", "--setting-sources",
				"", "--max-turns", "2");
	}

	@Test
	void thePromptIsNotAnArgument() {
		ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder().prompt("a very long prompt").build();

		assertThat(request.toArguments()).as("the prompt goes to stdin, not argv").doesNotContain("a very long prompt");
	}

	@Test
	void defaultsAreIsolatedEvenForDirectCoreCalls() {
		var request = ClaudeCodeCliRequest.builder().prompt("hi").build();
		assertThat(request.isolation()).isEqualTo(IsolationPolicy.ISOLATED);
		assertThat(request.toArguments()).contains("--safe-mode", "--strict-mcp-config", "--disable-slash-commands")
			.containsSubsequence("--tools", "").containsSubsequence("--setting-sources", "")
			.containsSubsequence("--system-prompt", "You are a helpful assistant.");
	}

	@Test
	void rejectsExternalContextBeforeAnyExecutorCanSeeTheRequest() {
		assertThatThrownBy(() -> ClaudeCodeCliRequest.builder().prompt("hi").tools(List.of("Read")).build())
			.hasMessageContaining("CUSTOM");
		assertThatThrownBy(() -> ClaudeCodeCliRequest.builder().prompt("hi").settingSources(List.of("user")).build())
			.hasMessageContaining("CUSTOM");
		for (String flag : List.of("--settings=x", "--mcp-config", "--tools=Read", "--safe-mode=false", "--unknown", "--")) {
			assertThatThrownBy(() -> ClaudeCodeCliRequest.builder().prompt("hi").extraArgs(List.of(flag)).build())
				.as(flag).isInstanceOf(com.iskeru.springai.claudecode.ClaudeCodeException.class);
		}
	}

	@Test
	void neitherPolicyCanOverrideFramingOrResumeASession() {
		for (var policy : IsolationPolicy.values()) {
			for (String flag : List.of("--output-format=text", "--input-format", "-p", "--verbose", "--resume=abc", "-c")) {
				assertThatThrownBy(() -> ClaudeCodeCliRequest.builder().prompt("hi").isolation(policy)
					.extraArgs(List.of(flag)).build()).hasMessageContaining("owned by the transport");
			}
		}
	}

	@Test
	void allowedFlagParsesSplitAndEqualsValuesWithoutSwallowingAnotherFlag() {
		for (var arguments : List.of(List.of("--max-turns", "2"), List.of("--max-turns=2"))) {
			assertThat(ClaudeCodeCliRequest.builder().prompt("hi").extraArgs(arguments).build().extraArgs()).isEqualTo(arguments);
		}
		for (var arguments : List.of(List.of("--max-turns"), List.of("--max-turns=0"),
				List.of("--max-turns", "--settings=x"), List.of("--max-turns=999999999999"))) {
			assertThatThrownBy(() -> ClaudeCodeCliRequest.builder().prompt("hi").extraArgs(arguments).build())
				.hasMessageContaining("positive integer");
		}
	}

}
