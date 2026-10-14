package com.iskeru.springai.claudecode;

import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Builds the Spring AI {@link Usage} for a CLI response — the Spring AI 2.0 twin.
 *
 * <p>
 * This class is <strong>duplicated per adapter module</strong>, like
 * {@link ClaudeCodeChatOptions}, because {@code DefaultUsage} gained its cache token pair
 * in 2.0: the six-argument constructor used here does not exist on 1.1.x. Both twins expose
 * exactly {@link #of(int, int, ClaudeCodeCliResponse.Usage, long, long)}, which is the whole
 * surface the shared {@link ClaudeCodeChatModel} touches.
 */
public final class ClaudeCodeUsage {

	private ClaudeCodeUsage() {
	}

	/**
	 * @param promptTokens input tokens, cache reads and cache writes already folded in
	 * @param completionTokens output tokens
	 * @param nativeUsage the CLI's own accounting, exposed via {@link Usage#getNativeUsage()}
	 * @param cacheReadInputTokens tokens served from the prompt cache
	 * @param cacheWriteInputTokens tokens written to the prompt cache
	 */
	public static Usage of(int promptTokens, int completionTokens, ClaudeCodeCliResponse.Usage nativeUsage,
			long cacheReadInputTokens, long cacheWriteInputTokens) {
		return new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens, nativeUsage,
				cacheReadInputTokens, cacheWriteInputTokens);
	}

}
