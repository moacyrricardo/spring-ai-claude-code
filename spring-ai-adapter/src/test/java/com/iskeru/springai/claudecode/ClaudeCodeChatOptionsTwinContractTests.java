package com.iskeru.springai.claudecode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.prompt.ChatOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Guards the contract between the two {@code ClaudeCodeChatOptions} twins.
 *
 * <p>
 * This class is a shared adapter source, so it runs against whichever twin its module
 * carries — the Spring AI 2.0 one in {@code spring-ai-claudecode-p}, the 1.1 one in
 * {@code spring-ai-claudecode-p-1x}. Ordinary shared code already fails to compile if a
 * twin loses something it calls; these tests widen that to the whole shared-visible
 * surface, including members only a consumer would reach for, so the twins cannot drift
 * apart quietly in a module that still happens to compile.
 *
 * <p>
 * Only the supertype wiring is allowed to differ: 1.1 implements the abstract generic
 * {@code copy()} and a non-generic {@code Builder}, 2.0 implements {@code mutate()} and a
 * self-typed {@code Builder} with {@code clone()}.
 */
class ClaudeCodeChatOptionsTwinContractTests {

	@Test
	void exposesTheSharedStaticFactories() throws Exception {
		Method merge = ClaudeCodeChatOptions.class.getMethod("merge", ChatOptions.class, ChatOptions.class);
		assertThat(Modifier.isStatic(merge.getModifiers())).isTrue();
		assertThat(merge.getReturnType()).isEqualTo(ClaudeCodeChatOptions.class);

		Method builder = ClaudeCodeChatOptions.class.getMethod("builder");
		assertThat(Modifier.isStatic(builder.getModifiers())).isTrue();
		assertThat(builder.getReturnType()).isEqualTo(ClaudeCodeChatOptions.Builder.class);
	}

	@Test
	void exposesCopyAndMutateWhicheverGenerationDeclaresThem() throws Exception {
		// Erasure differs — 1.1's `<T extends ChatOptions> T copy()` erases to ChatOptions,
		// 2.0's returns the concrete type — so the assertion is on assignability.
		Method copy = ClaudeCodeChatOptions.class.getMethod("copy");
		assertThat(ChatOptions.class).isAssignableFrom(copy.getReturnType());

		Method mutate = ClaudeCodeChatOptions.class.getMethod("mutate");
		assertThat(ChatOptions.Builder.class).isAssignableFrom(mutate.getReturnType());

		ClaudeCodeChatOptions original = ClaudeCodeChatOptions.builder().model("sonnet").effort("high").build();
		assertThatCode(() -> {
			ChatOptions copied = original.copy();
			assertThat(copied.getModel()).isEqualTo("sonnet");
			assertThat(((ClaudeCodeChatOptions) original.mutate().build()).getEffort()).isEqualTo("high");
		}).doesNotThrowAnyException();
	}

	@Test
	void exposesEveryGetterAndBuilderSetterTheAdapterReads() {
		List<String> getters = List.of("getModel", "getTemperature", "getTopP", "getTopK", "getMaxTokens",
				"getStopSequences", "getFrequencyPenalty", "getPresencePenalty", "getSystemPrompt",
				"getAppendSystemPrompt", "getTools", "getEffort", "getFallbackModels", "getMaxBudgetUsd",
				"getJsonSchema", "getSettingSources", "getExtraArgs");
		assertThat(getters).allSatisfy(name -> assertThatCode(() -> ClaudeCodeChatOptions.class.getMethod(name))
			.as("ClaudeCodeChatOptions.%s()", name)
			.doesNotThrowAnyException());

		List<String> stringSetters = List.of("model", "systemPrompt", "appendSystemPrompt", "effort", "jsonSchema");
		assertThat(stringSetters)
			.allSatisfy(name -> assertThatCode(
					() -> ClaudeCodeChatOptions.Builder.class.getMethod(name, String.class))
				.as("ClaudeCodeChatOptions.Builder.%s(String)", name)
				.doesNotThrowAnyException());

		List<String> listSetters = List.of("stopSequences", "tools", "fallbackModels", "settingSources", "extraArgs");
		assertThat(listSetters)
			.allSatisfy(name -> assertThatCode(() -> ClaudeCodeChatOptions.Builder.class.getMethod(name, List.class))
				.as("ClaudeCodeChatOptions.Builder.%s(List)", name)
				.doesNotThrowAnyException());
	}

}
