package com.iskeru.springai.claudecode;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * The default {@link ConversationRenderer}.
 *
 * <p>
 * System messages are joined and handed to {@code --system-prompt}. A conversation whose
 * only remaining message is a single user turn is passed through verbatim — the common
 * case, and the one that keeps fixtures readable. Anything longer is rendered as a tagged
 * transcript:
 *
 * <pre>{@code
 * The following is an ongoing conversation. Reply as the assistant to the last user message.
 *
 * <conversation>
 * <user>What is 2 + 2?</user>
 * <assistant>4.</assistant>
 * <user>And times 3?</user>
 * </conversation>
 * }</pre>
 */
public class DefaultConversationRenderer implements ConversationRenderer {

	private static final String TRANSCRIPT_PREAMBLE = "The following is an ongoing conversation. "
			+ "Reply as the assistant to the last user message.";

	@Override
	public RenderedPrompt render(List<Message> messages) {
		List<String> systemParts = new ArrayList<>();
		List<Message> turns = new ArrayList<>();

		for (Message message : messages) {
			if (message.getMessageType() == MessageType.SYSTEM) {
				String text = message.getText();
				if (text != null && !text.isBlank()) {
					systemParts.add(text);
				}
			}
			else {
				turns.add(message);
			}
		}

		String systemPrompt = systemParts.isEmpty() ? null : String.join("\n\n", systemParts);

		if (turns.isEmpty()) {
			throw new ClaudeCodeException(
					"The prompt contains no user message. The Claude Code CLI needs something to respond to; "
							+ "a system message alone is not enough.");
		}

		if (turns.size() == 1 && turns.get(0).getMessageType() == MessageType.USER) {
			return new RenderedPrompt(systemPrompt, nullSafe(turns.get(0).getText()));
		}

		return new RenderedPrompt(systemPrompt, renderTranscript(turns));
	}

	protected String renderTranscript(List<Message> turns) {
		StringBuilder transcript = new StringBuilder(TRANSCRIPT_PREAMBLE).append("\n\n<conversation>\n");
		for (Message turn : turns) {
			String tag = tagFor(turn.getMessageType());
			transcript.append('<').append(tag).append('>').append(nullSafe(turn.getText())).append("</").append(tag)
				.append(">\n");
		}
		return transcript.append("</conversation>").toString();
	}

	protected String tagFor(MessageType type) {
		return switch (type) {
			case USER -> "user";
			case ASSISTANT -> "assistant";
			case TOOL -> "tool_response";
			// Filtered out before we get here; kept exhaustive so a new MessageType fails
			// loudly rather than silently rendering as something misleading.
			case SYSTEM -> "system";
		};
	}

	private static String nullSafe(String text) {
		return (text == null) ? "" : text;
	}

}
