package com.iskeru.springai.claudecode;

import org.junit.jupiter.api.Test;

import com.iskeru.springai.claudecode.cli.RecordingFakeCli;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Spring AI 2.0 half of the usage mapping: {@code DefaultUsage} carries the cache token
 * pair as first-class fields here, so they survive as typed getters. The 1.x adapter has its
 * own version of this test asserting the same numbers through the native usage instead.
 */
class ClaudeCodeUsageTests {

	@Test
	void exposesCacheTokensAsFirstClassUsageFields() {
		ChatResponse response = ClaudeCodeChatModel.builder()
			.cli(new RecordingFakeCli())
			.build()
			.call(new Prompt("hi"));

		Usage usage = response.getMetadata().getUsage();
		assertThat(usage.getCacheReadInputTokens()).isEqualTo(3289L);
		assertThat(usage.getCacheWriteInputTokens()).isEqualTo(7933L);
	}

}
