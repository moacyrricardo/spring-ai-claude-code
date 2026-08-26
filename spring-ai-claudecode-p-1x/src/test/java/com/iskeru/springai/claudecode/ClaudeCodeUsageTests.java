package com.iskeru.springai.claudecode;

import org.junit.jupiter.api.Test;

import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;
import com.iskeru.springai.claudecode.cli.RecordingFakeCli;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring AI 1.1 half of the usage mapping: {@code Usage} has no cache token getters
 * here, so the same numbers the 2.0 adapter exposes as typed fields have to be reachable
 * through the native usage instead.
 */
class ClaudeCodeUsageTests {

	@Test
	void exposesCacheTokensThroughTheNativeUsage() {
		ChatResponse response = ClaudeCodeChatModel.builder()
			.cli(new RecordingFakeCli())
			.build()
			.call(new Prompt("hi"));

		Usage usage = response.getMetadata().getUsage();
		assertThat(usage.getNativeUsage()).isInstanceOf(ClaudeCodeCliResponse.Usage.class);
		ClaudeCodeCliResponse.Usage nativeUsage = (ClaudeCodeCliResponse.Usage) usage.getNativeUsage();
		assertThat(nativeUsage.cacheReadInputTokens()).isEqualTo(3289L);
		assertThat(nativeUsage.cacheCreationInputTokens()).isEqualTo(7933L);
	}

}
