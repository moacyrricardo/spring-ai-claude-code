package com.iskeru.springai.claudecode;

import java.util.List;

import org.springframework.ai.chat.messages.Message;

/**
 * Flattens a Spring AI message list into the two things a {@code claude -p} invocation
 * accepts: a system prompt and a single block of user text.
 *
 * <p>
 * The CLI has no notion of a message array — each {@code -p} run takes one prompt. Prior
 * turns therefore have to be serialised into that prompt. The default rendering is
 * {@link DefaultConversationRenderer}; supply your own if your assertions depend on a
 * particular transcript shape.
 */
@FunctionalInterface
public interface ConversationRenderer {

	/**
	 * @param messages the prompt's instructions, in order
	 * @return the system prompt (nullable) and the user text
	 */
	RenderedPrompt render(List<Message> messages);

	/**
	 * @param systemPrompt text for {@code --system-prompt}, or {@code null} if the
	 * conversation carried no system message
	 * @param userPrompt the text written to the CLI's stdin
	 */
	record RenderedPrompt(String systemPrompt, String userPrompt) {
	}

}
