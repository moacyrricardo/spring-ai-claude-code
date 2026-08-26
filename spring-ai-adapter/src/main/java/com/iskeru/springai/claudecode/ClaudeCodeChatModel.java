package com.iskeru.springai.claudecode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.core.publisher.Flux;

import com.iskeru.springai.claudecode.cli.ClaudeCodeCli;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A Spring AI {@link ChatModel} that answers by shelling out to the locally installed
 * {@code claude} CLI in non-interactive mode ({@code claude -p}).
 *
 * <p>
 * The point is cost: the CLI bills against the developer's existing Claude Code
 * subscription, so a test suite can exercise real model behaviour without anyone
 * provisioning or burning API keys. Pair it with the record/replay layer
 * ({@link com.iskeru.springai.claudecode.replay.ReplayingClaudeCodeCli}) and CI replays
 * captured responses offline, deterministically, at zero cost.
 *
 * <h2>Not a production model</h2>
 * This is test infrastructure. Each call spawns a process (hundreds of milliseconds of
 * overhead before the model even starts), sampling parameters cannot be forwarded, and
 * throughput is bounded by how many CLI processes the machine will tolerate.
 *
 * <h2>Agent behaviour is off by default</h2>
 * Claude Code is a coding agent; a chat model is not. Unless told otherwise this model
 * runs the CLI with all tools disabled, no settings files loaded, and a plain assistant
 * system prompt in place of the agent one — which is both what a {@code ChatModel} should
 * behave like and roughly 11k fewer input tokens per call. See
 * {@link ClaudeCodeChatOptions} to opt back in.
 *
 * <pre>{@code
 * ChatModel model = ClaudeCodeChatModel.builder()
 *     .cli(ProcessClaudeCodeCli.builder().build())
 *     .defaultOptions(ClaudeCodeChatOptions.builder().model("sonnet").build())
 *     .build();
 *
 * String answer = model.call("What is 2 + 2?");
 * }</pre>
 */
public class ClaudeCodeChatModel implements ChatModel {

	/**
	 * Replaces Claude Code's agent system prompt when the caller supplies neither a
	 * {@code SystemMessage} nor {@link ClaudeCodeChatOptions#getSystemPrompt()}.
	 */
	public static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant.";

	private static final Log logger = LogFactory.getLog(ClaudeCodeChatModel.class);

	private final ClaudeCodeCli cli;

	private final ClaudeCodeChatOptions defaultOptions;

	private final ConversationRenderer conversationRenderer;

	/** Sampling options are silently ignored by the CLI; warn about that once, not per call. */
	private final AtomicBoolean warnedAboutSamplingOptions = new AtomicBoolean();

	protected ClaudeCodeChatModel(Builder builder) {
		this.cli = builder.cli;
		this.defaultOptions = builder.defaultOptions;
		this.conversationRenderer = builder.conversationRenderer;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		ClaudeCodeChatOptions options = ClaudeCodeChatOptions.merge(this.defaultOptions, prompt.getOptions());
		warnAboutUnsupportedOptions(options);

		ConversationRenderer.RenderedPrompt rendered = this.conversationRenderer.render(prompt.getInstructions());

		ClaudeCodeCliResponse response = this.cli.execute(toCliRequest(rendered, options));
		return toChatResponse(response);
	}

	/**
	 * Emits the completed response as a single element.
	 *
	 * <p>
	 * This is <strong>not</strong> streaming: the CLI runs to completion first and the
	 * whole answer arrives in one chunk. It exists so that code under test which calls
	 * {@code ChatClient.stream()} keeps working against this model instead of hitting the
	 * {@code UnsupportedOperationException} the interface default would throw. Assertions
	 * about chunk boundaries or time-to-first-token will not be meaningful.
	 */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return Flux.defer(() -> Flux.just(call(prompt)));
	}

	/**
	 * The model's default options.
	 *
	 * <p>
	 * Deliberately not annotated {@code @Override}: on Spring AI 2.0 this overrides
	 * {@code ChatModel.getOptions()}, but that method does not exist on 1.1.x, where this
	 * is simply an extra public method. Dropping the annotation is what lets this one
	 * source file compile against both generations.
	 */
	public ChatOptions getOptions() {
		return this.defaultOptions.copy();
	}

	protected ClaudeCodeCliRequest toCliRequest(ConversationRenderer.RenderedPrompt rendered,
			ClaudeCodeChatOptions options) {
		// A SystemMessage in the Prompt is more specific than a configured default, so it
		// wins; falling through to DEFAULT_SYSTEM_PROMPT is what keeps the CLI from
		// behaving like a coding agent.
		String systemPrompt = (rendered.systemPrompt() != null) ? rendered.systemPrompt() : options.getSystemPrompt();
		if (systemPrompt == null) {
			systemPrompt = DEFAULT_SYSTEM_PROMPT;
		}

		return ClaudeCodeCliRequest.builder()
			.prompt(rendered.userPrompt())
			.systemPrompt(systemPrompt)
			.appendSystemPrompt(options.getAppendSystemPrompt())
			.model(options.getModel())
			// Default to no tools and no settings files: a ChatModel should answer, not
			// edit files, and its answers should not depend on the developer's personal
			// Claude Code configuration. `tools(List.of("default"))` opts back in.
			.tools((options.getTools() != null) ? options.getTools() : List.of())
			.settingSources((options.getSettingSources() != null) ? options.getSettingSources() : List.of())
			.effort(options.getEffort())
			.fallbackModels(options.getFallbackModels())
			.maxBudgetUsd(options.getMaxBudgetUsd())
			.jsonSchema(options.getJsonSchema())
			.extraArgs(options.getExtraArgs())
			.build();
	}

	protected ChatResponse toChatResponse(ClaudeCodeCliResponse response) {
		Map<String, Object> details = new LinkedHashMap<>();
		details.put("replayed", response.replayed());
		putIfPresent(details, "sessionId", response.sessionId());
		putIfPresent(details, "totalCostUsd", response.totalCostUsd());
		putIfPresent(details, "durationMs", response.durationMs());
		putIfPresent(details, "numTurns", response.numTurns());

		ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
			.finishReason(response.stopReason())
			.metadata(details)
			.build();

		Generation generation = new Generation(new AssistantMessage(response.result()), generationMetadata);

		ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
			.id((response.sessionId() != null) ? response.sessionId() : "")
			.model((response.model() != null) ? response.model() : "")
			.usage(toUsage(response.usage()));
		details.forEach(metadata::keyValue);

		return new ChatResponse(List.of(generation), metadata.build());
	}

	/**
	 * Maps the CLI's token accounting onto Spring AI's.
	 *
	 * <p>
	 * Cache reads and cache writes are input tokens, so they are folded into the prompt
	 * count; the untouched CLI numbers remain available via
	 * {@link org.springframework.ai.chat.metadata.Usage#getNativeUsage()} for tests that
	 * need to distinguish them.
	 *
	 * <p>
	 * Construction goes through {@link ClaudeCodeUsage}, which is duplicated per adapter:
	 * only Spring AI 2.0's {@code DefaultUsage} carries the cache token pair as first-class
	 * fields.
	 */
	protected Usage toUsage(ClaudeCodeCliResponse.Usage usage) {
		if (usage == null) {
			return ClaudeCodeUsage.of(0, 0, null, 0L, 0L);
		}
		long cacheRead = orZero(usage.cacheReadInputTokens());
		long cacheWrite = orZero(usage.cacheCreationInputTokens());
		int promptTokens = (int) (orZero(usage.inputTokens()) + cacheRead + cacheWrite);
		int completionTokens = (int) orZero(usage.outputTokens());
		return ClaudeCodeUsage.of(promptTokens, completionTokens, usage, cacheRead, cacheWrite);
	}

	private void warnAboutUnsupportedOptions(ClaudeCodeChatOptions options) {
		boolean anySet = options.getTemperature() != null || options.getTopP() != null || options.getTopK() != null
				|| options.getMaxTokens() != null || options.getStopSequences() != null
				|| options.getFrequencyPenalty() != null || options.getPresencePenalty() != null;
		if (anySet && this.warnedAboutSamplingOptions.compareAndSet(false, true)) {
			logger.warn("Sampling options (temperature, topP, topK, maxTokens, stopSequences, frequencyPenalty, "
					+ "presencePenalty) are set but the Claude Code CLI cannot forward them. They will be ignored. "
					+ "Tests that depend on these need an API-backed ChatModel.");
		}
	}

	private static void putIfPresent(Map<String, Object> target, String key, Object value) {
		if (value != null) {
			target.put(key, value);
		}
	}

	private static long orZero(Number value) {
		return (value == null) ? 0L : value.longValue();
	}

	public static final class Builder {

		private ClaudeCodeCli cli;

		private ClaudeCodeChatOptions defaultOptions = ClaudeCodeChatOptions.builder().build();

		private ConversationRenderer conversationRenderer = new DefaultConversationRenderer();

		private Builder() {
		}

		/** The CLI executor. Required. Wrap it to add record/replay. */
		public Builder cli(ClaudeCodeCli cli) {
			this.cli = cli;
			return this;
		}

		/** Options applied to every call, overridable per {@link Prompt}. */
		public Builder defaultOptions(ClaudeCodeChatOptions defaultOptions) {
			this.defaultOptions = defaultOptions;
			return this;
		}

		/** How multi-turn conversations are flattened into a single CLI prompt. */
		public Builder conversationRenderer(ConversationRenderer conversationRenderer) {
			this.conversationRenderer = conversationRenderer;
			return this;
		}

		public ClaudeCodeChatModel build() {
			if (this.cli == null) {
				throw new IllegalStateException("A ClaudeCodeCli must be provided");
			}
			if (this.defaultOptions == null) {
				throw new IllegalStateException("defaultOptions must not be null");
			}
			if (this.conversationRenderer == null) {
				throw new IllegalStateException("conversationRenderer must not be null");
			}
			return new ClaudeCodeChatModel(this);
		}

	}

}
