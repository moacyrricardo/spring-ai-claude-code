package com.iskeru.springai.claudecode;

import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;

import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Builds the Spring AI {@link Usage} for a CLI response — the Spring AI 1.1 twin.
 *
 * <p>
 * 1.1's {@code DefaultUsage} has no cache token fields (they arrive in 2.0 together with
 * {@code Usage.getCacheReadInputTokens()}), so the two cache counts are not lost but not
 * first-class either: they stay folded into the prompt count, and the exact numbers remain
 * reachable through {@link Usage#getNativeUsage()}, which is the CLI's own accounting.
 */
public final class ClaudeCodeUsage {

	private ClaudeCodeUsage() {
	}

	/**
	 * @param promptTokens input tokens, cache reads and cache writes already folded in
	 * @param completionTokens output tokens
	 * @param nativeUsage the CLI's own accounting, exposed via {@link Usage#getNativeUsage()}
	 * @param cacheReadInputTokens tokens served from the prompt cache; unrepresentable on
	 * 1.1, see {@code nativeUsage}
	 * @param cacheWriteInputTokens tokens written to the prompt cache; likewise
	 */
	public static Usage of(int promptTokens, int completionTokens, ClaudeCodeCliResponse.Usage nativeUsage,
			long cacheReadInputTokens, long cacheWriteInputTokens) {
		return new DefaultUsage(promptTokens, completionTokens, promptTokens + completionTokens, nativeUsage);
	}

}
