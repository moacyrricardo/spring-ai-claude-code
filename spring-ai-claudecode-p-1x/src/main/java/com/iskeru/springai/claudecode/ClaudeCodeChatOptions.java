package com.iskeru.springai.claudecode;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * {@link ChatOptions} for the {@link ClaudeCodeChatModel}, covering both the portable
 * Spring AI knobs and the Claude Code CLI's own flags — the <strong>Spring AI 1.1</strong>
 * twin.
 *
 * <h2>Why this class is duplicated</h2>
 * Everything else in the adapter is one source file shared by both modules. This class
 * cannot be, because its supertype moved: 1.1 declares an abstract
 * {@code <T extends ChatOptions> T copy()} and a non-generic {@code ChatOptions.Builder},
 * while 2.0 dropped {@code copy()} for {@code mutate()} and made the builder self-typed.
 * The twins must keep the same surface everywhere shared sources and shared tests touch
 * them — {@code merge}, {@code copy}, {@code mutate}, {@code builder} and every getter —
 * and the shared tests running in both modules are what enforces that.
 *
 * <h2>Sampling parameters are not supported</h2>
 * {@code temperature}, {@code topP}, {@code topK}, {@code maxTokens},
 * {@code stopSequences}, {@code frequencyPenalty} and {@code presencePenalty} are part of
 * the {@link ChatOptions} contract, so they can be set and read back here, but the
 * {@code claude} CLI exposes no way to forward them and they have <strong>no effect on the
 * response</strong>. {@link ClaudeCodeChatModel} logs a warning the first time it sees one
 * set. If a test depends on a specific temperature, it needs a real API-backed model.
 */
public class ClaudeCodeChatOptions implements ChatOptions {

	// --- Portable ChatOptions (accepted, not forwarded — see class javadoc) ---

	private String model;

	private Double temperature;

	private Double topP;

	private Integer topK;

	private Integer maxTokens;

	private List<String> stopSequences;

	private Double frequencyPenalty;

	private Double presencePenalty;

	// --- Claude Code CLI specifics ---

	private String systemPrompt;

	private String appendSystemPrompt;

	private List<String> tools;

	private String effort;

	private List<String> fallbackModels;

	private Double maxBudgetUsd;

	private String jsonSchema;

	private List<String> settingSources;

	private List<String> extraArgs;

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public String getModel() {
		return this.model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	@Override
	public Double getTemperature() {
		return this.temperature;
	}

	public void setTemperature(Double temperature) {
		this.temperature = temperature;
	}

	@Override
	public Double getTopP() {
		return this.topP;
	}

	public void setTopP(Double topP) {
		this.topP = topP;
	}

	@Override
	public Integer getTopK() {
		return this.topK;
	}

	public void setTopK(Integer topK) {
		this.topK = topK;
	}

	@Override
	public Integer getMaxTokens() {
		return this.maxTokens;
	}

	public void setMaxTokens(Integer maxTokens) {
		this.maxTokens = maxTokens;
	}

	@Override
	public List<String> getStopSequences() {
		return this.stopSequences;
	}

	public void setStopSequences(List<String> stopSequences) {
		this.stopSequences = stopSequences;
	}

	@Override
	public Double getFrequencyPenalty() {
		return this.frequencyPenalty;
	}

	public void setFrequencyPenalty(Double frequencyPenalty) {
		this.frequencyPenalty = frequencyPenalty;
	}

	@Override
	public Double getPresencePenalty() {
		return this.presencePenalty;
	}

	public void setPresencePenalty(Double presencePenalty) {
		this.presencePenalty = presencePenalty;
	}

	/**
	 * Replaces Claude Code's own agent system prompt. Set this (or leave the model's
	 * default in place) to make the CLI behave as a plain LLM rather than a coding agent.
	 * Any {@code SystemMessage} in the {@code Prompt} takes precedence over this.
	 */
	public String getSystemPrompt() {
		return this.systemPrompt;
	}

	public void setSystemPrompt(String systemPrompt) {
		this.systemPrompt = systemPrompt;
	}

	public String getAppendSystemPrompt() {
		return this.appendSystemPrompt;
	}

	public void setAppendSystemPrompt(String appendSystemPrompt) {
		this.appendSystemPrompt = appendSystemPrompt;
	}

	/**
	 * Built-in CLI tools to allow. {@code null} keeps the CLI default set; an empty list
	 * — the model's default — disables every tool, which is what a plain text completion
	 * wants.
	 */
	public List<String> getTools() {
		return this.tools;
	}

	public void setTools(List<String> tools) {
		this.tools = tools;
	}

	/** Reasoning effort: {@code low}, {@code medium}, {@code high}, {@code xhigh}, {@code max}. */
	public String getEffort() {
		return this.effort;
	}

	public void setEffort(String effort) {
		this.effort = effort;
	}

	public List<String> getFallbackModels() {
		return this.fallbackModels;
	}

	public void setFallbackModels(List<String> fallbackModels) {
		this.fallbackModels = fallbackModels;
	}

	/** Hard spend ceiling for a single invocation, in USD. */
	public Double getMaxBudgetUsd() {
		return this.maxBudgetUsd;
	}

	public void setMaxBudgetUsd(Double maxBudgetUsd) {
		this.maxBudgetUsd = maxBudgetUsd;
	}

	/** A JSON Schema (as a string) the CLI will validate the response against. */
	public String getJsonSchema() {
		return this.jsonSchema;
	}

	public void setJsonSchema(String jsonSchema) {
		this.jsonSchema = jsonSchema;
	}

	/**
	 * Which settings files the CLI loads ({@code user}, {@code project}, {@code local}).
	 * The model defaults to an empty list so that results do not depend on the developer's
	 * personal Claude Code configuration.
	 */
	public List<String> getSettingSources() {
		return this.settingSources;
	}

	public void setSettingSources(List<String> settingSources) {
		this.settingSources = settingSources;
	}

	/** Raw CLI flags appended verbatim, for anything not modelled above. */
	public List<String> getExtraArgs() {
		return this.extraArgs;
	}

	public void setExtraArgs(List<String> extraArgs) {
		this.extraArgs = extraArgs;
	}

	/**
	 * Not annotated {@code @Override}: {@code ChatOptions.mutate()} arrives in 2.0. Here it
	 * is simply the same method by hand, so shared code calling it compiles either way.
	 */
	public Builder mutate() {
		return new Builder(copyOf(this));
	}

	/**
	 * Returns an independent copy of these options.
	 *
	 * <p>
	 * On 1.1 this implements {@code ChatOptions}'s abstract, generically self-typed
	 * {@code copy()}; the cast is safe because the returned instance really is a
	 * {@code ClaudeCodeChatOptions} and callers infer {@code T} from their own target type.
	 */
	@Override
	@SuppressWarnings("unchecked")
	public <T extends ChatOptions> T copy() {
		return (T) copyOf(this);
	}

	private static ClaudeCodeChatOptions copyOf(ClaudeCodeChatOptions source) {
		ClaudeCodeChatOptions copy = new ClaudeCodeChatOptions();
		copy.model = source.model;
		copy.temperature = source.temperature;
		copy.topP = source.topP;
		copy.topK = source.topK;
		copy.maxTokens = source.maxTokens;
		copy.stopSequences = (source.stopSequences == null) ? null : new ArrayList<>(source.stopSequences);
		copy.frequencyPenalty = source.frequencyPenalty;
		copy.presencePenalty = source.presencePenalty;
		copy.systemPrompt = source.systemPrompt;
		copy.appendSystemPrompt = source.appendSystemPrompt;
		copy.tools = (source.tools == null) ? null : new ArrayList<>(source.tools);
		copy.effort = source.effort;
		copy.fallbackModels = (source.fallbackModels == null) ? null : new ArrayList<>(source.fallbackModels);
		copy.maxBudgetUsd = source.maxBudgetUsd;
		copy.jsonSchema = source.jsonSchema;
		copy.settingSources = (source.settingSources == null) ? null : new ArrayList<>(source.settingSources);
		copy.extraArgs = (source.extraArgs == null) ? null : new ArrayList<>(source.extraArgs);
		return copy;
	}

	/**
	 * Overlays {@code override} onto {@code base}, with every non-null value in
	 * {@code override} winning. Used to apply a request's runtime options over the model's
	 * defaults.
	 *
	 * <p>
	 * {@code override} does not have to be a {@code ClaudeCodeChatOptions}: any
	 * {@link ChatOptions} — such as the one {@code ChatClient} builds — contributes its
	 * portable fields, and CLI-specific fields are taken only when it is one of ours.
	 * @param base the defaults, may be {@code null}
	 * @param override the higher-precedence options, may be {@code null}
	 * @return a new instance; never {@code null}
	 */
	public static ClaudeCodeChatOptions merge(ChatOptions base, ChatOptions override) {
		ClaudeCodeChatOptions merged = (base instanceof ClaudeCodeChatOptions ours) ? copyOf(ours)
				: applyPortable(new ClaudeCodeChatOptions(), base);
		if (override == null) {
			return merged;
		}
		applyPortable(merged, override);
		if (override instanceof ClaudeCodeChatOptions ours) {
			if (ours.systemPrompt != null) {
				merged.systemPrompt = ours.systemPrompt;
			}
			if (ours.appendSystemPrompt != null) {
				merged.appendSystemPrompt = ours.appendSystemPrompt;
			}
			if (ours.tools != null) {
				merged.tools = new ArrayList<>(ours.tools);
			}
			if (ours.effort != null) {
				merged.effort = ours.effort;
			}
			if (ours.fallbackModels != null) {
				merged.fallbackModels = new ArrayList<>(ours.fallbackModels);
			}
			if (ours.maxBudgetUsd != null) {
				merged.maxBudgetUsd = ours.maxBudgetUsd;
			}
			if (ours.jsonSchema != null) {
				merged.jsonSchema = ours.jsonSchema;
			}
			if (ours.settingSources != null) {
				merged.settingSources = new ArrayList<>(ours.settingSources);
			}
			if (ours.extraArgs != null) {
				merged.extraArgs = new ArrayList<>(ours.extraArgs);
			}
		}
		return merged;
	}

	private static ClaudeCodeChatOptions applyPortable(ClaudeCodeChatOptions target, ChatOptions source) {
		if (source == null) {
			return target;
		}
		if (source.getModel() != null) {
			target.model = source.getModel();
		}
		if (source.getTemperature() != null) {
			target.temperature = source.getTemperature();
		}
		if (source.getTopP() != null) {
			target.topP = source.getTopP();
		}
		if (source.getTopK() != null) {
			target.topK = source.getTopK();
		}
		if (source.getMaxTokens() != null) {
			target.maxTokens = source.getMaxTokens();
		}
		if (source.getStopSequences() != null) {
			target.stopSequences = new ArrayList<>(source.getStopSequences());
		}
		if (source.getFrequencyPenalty() != null) {
			target.frequencyPenalty = source.getFrequencyPenalty();
		}
		if (source.getPresencePenalty() != null) {
			target.presencePenalty = source.getPresencePenalty();
		}
		return target;
	}

	/**
	 * On 1.1 {@code ChatOptions.Builder} is a plain interface, so the self-type parameter
	 * the 2.0 twin uses is absent; the setters return this concrete {@code Builder} as a
	 * covariant override instead, which keeps the shared-visible surface identical.
	 */
	public static final class Builder implements ChatOptions.Builder {

		private ClaudeCodeChatOptions options;

		private Builder() {
			this(new ClaudeCodeChatOptions());
		}

		private Builder(ClaudeCodeChatOptions options) {
			this.options = options;
		}

		/** Kept for parity with the 2.0 twin, where it overrides the builder interface. */
		@Override
		public Builder clone() {
			return new Builder(copyOf(this.options));
		}

		@Override
		public Builder model(String model) {
			this.options.model = model;
			return this;
		}

		@Override
		public Builder temperature(Double temperature) {
			this.options.temperature = temperature;
			return this;
		}

		@Override
		public Builder topP(Double topP) {
			this.options.topP = topP;
			return this;
		}

		@Override
		public Builder topK(Integer topK) {
			this.options.topK = topK;
			return this;
		}

		@Override
		public Builder maxTokens(Integer maxTokens) {
			this.options.maxTokens = maxTokens;
			return this;
		}

		@Override
		public Builder stopSequences(List<String> stopSequences) {
			this.options.stopSequences = stopSequences;
			return this;
		}

		@Override
		public Builder frequencyPenalty(Double frequencyPenalty) {
			this.options.frequencyPenalty = frequencyPenalty;
			return this;
		}

		@Override
		public Builder presencePenalty(Double presencePenalty) {
			this.options.presencePenalty = presencePenalty;
			return this;
		}

		/** Kept for parity with the 2.0 twin, where it overrides the builder interface. */
		public Builder combineWith(ChatOptions.Builder other) {
			if (other instanceof Builder that) {
				this.options = merge(this.options, that.options);
			}
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.options.systemPrompt = systemPrompt;
			return this;
		}

		public Builder appendSystemPrompt(String appendSystemPrompt) {
			this.options.appendSystemPrompt = appendSystemPrompt;
			return this;
		}

		public Builder tools(List<String> tools) {
			this.options.tools = tools;
			return this;
		}

		public Builder effort(String effort) {
			this.options.effort = effort;
			return this;
		}

		public Builder fallbackModels(List<String> fallbackModels) {
			this.options.fallbackModels = fallbackModels;
			return this;
		}

		public Builder maxBudgetUsd(Double maxBudgetUsd) {
			this.options.maxBudgetUsd = maxBudgetUsd;
			return this;
		}

		public Builder jsonSchema(String jsonSchema) {
			this.options.jsonSchema = jsonSchema;
			return this;
		}

		public Builder settingSources(List<String> settingSources) {
			this.options.settingSources = settingSources;
			return this;
		}

		public Builder extraArgs(List<String> extraArgs) {
			this.options.extraArgs = extraArgs;
			return this;
		}

		@Override
		public ClaudeCodeChatOptions build() {
			return copyOf(this.options);
		}

	}

}
