package com.iskeru.springai.claudecode.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A single non-interactive {@code claude -p} invocation, expressed in CLI terms.
 *
 * <p>
 * This is deliberately a flat, value-based description of the command line rather than a
 * chat abstraction: {@link com.iskeru.springai.claudecode.ClaudeCodeChatModel} owns the
 * translation from a Spring AI {@code Prompt}, and everything below this point is just
 * "run the binary".
 *
 * @param prompt the user-turn text, written to the process' stdin rather than passed as
 * an argv element so that long transcripts are not subject to {@code ARG_MAX}
 * @param systemPrompt replaces Claude Code's own agent system prompt via
 * {@code --system-prompt}; this is what turns the CLI from a coding agent into a plain
 * LLM. {@code null} leaves the default agent prompt in place
 * @param appendSystemPrompt appended to whatever system prompt is in effect
 * @param model a model alias ({@code sonnet}, {@code opus}, {@code haiku}) or a full id
 * @param tools {@code null} keeps the CLI default tool set; an empty list disables all
 * tools ({@code --tools ""}), which is the right choice for a pure text completion
 * @param effort reasoning effort: {@code low}, {@code medium}, {@code high}, {@code xhigh}
 * or {@code max}
 * @param fallbackModels models to fall back to when the primary is overloaded
 * @param maxBudgetUsd hard spend ceiling for this invocation
 * @param jsonSchema a JSON Schema the response must validate against
 * @param settingSources which settings files to load; an empty list loads none, keeping
 * the invocation independent of the developer's personal Claude Code configuration
 * @param extraArgs escape hatch for CLI flags this record does not model
 */
public record ClaudeCodeCliRequest(String prompt, String systemPrompt, String appendSystemPrompt, String model,
		List<String> tools, String effort, List<String> fallbackModels, Double maxBudgetUsd, String jsonSchema,
		List<String> settingSources, List<String> extraArgs) {

	public ClaudeCodeCliRequest {
		Objects.requireNonNull(prompt, "prompt must not be null");
		tools = (tools == null) ? null : List.copyOf(tools);
		fallbackModels = (fallbackModels == null) ? List.of() : List.copyOf(fallbackModels);
		settingSources = (settingSources == null) ? null : List.copyOf(settingSources);
		extraArgs = (extraArgs == null) ? List.of() : List.copyOf(extraArgs);
	}

	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Renders the argv for this request, excluding the executable itself and the flags
	 * that are invariant across every invocation ({@code -p},
	 * {@code --output-format json}).
	 */
	public List<String> toArguments() {
		List<String> args = new ArrayList<>();
		if (this.model != null) {
			args.add("--model");
			args.add(this.model);
		}
		if (this.systemPrompt != null) {
			args.add("--system-prompt");
			args.add(this.systemPrompt);
		}
		if (this.appendSystemPrompt != null) {
			args.add("--append-system-prompt");
			args.add(this.appendSystemPrompt);
		}
		if (this.tools != null) {
			args.add("--tools");
			// `--tools ""` is how the CLI spells "no tools at all".
			args.add(this.tools.isEmpty() ? "" : String.join(",", this.tools));
		}
		if (this.effort != null) {
			args.add("--effort");
			args.add(this.effort);
		}
		if (!this.fallbackModels.isEmpty()) {
			args.add("--fallback-model");
			args.add(String.join(",", this.fallbackModels));
		}
		if (this.maxBudgetUsd != null) {
			args.add("--max-budget-usd");
			args.add(this.maxBudgetUsd.toString());
		}
		if (this.jsonSchema != null) {
			args.add("--json-schema");
			args.add(this.jsonSchema);
		}
		if (this.settingSources != null) {
			args.add("--setting-sources");
			args.add(String.join(",", this.settingSources));
		}
		args.addAll(this.extraArgs);
		return Collections.unmodifiableList(args);
	}

	public static final class Builder {

		private String prompt;

		private String systemPrompt;

		private String appendSystemPrompt;

		private String model;

		private List<String> tools;

		private String effort;

		private List<String> fallbackModels;

		private Double maxBudgetUsd;

		private String jsonSchema;

		private List<String> settingSources;

		private List<String> extraArgs;

		public Builder prompt(String prompt) {
			this.prompt = prompt;
			return this;
		}

		public Builder systemPrompt(String systemPrompt) {
			this.systemPrompt = systemPrompt;
			return this;
		}

		public Builder appendSystemPrompt(String appendSystemPrompt) {
			this.appendSystemPrompt = appendSystemPrompt;
			return this;
		}

		public Builder model(String model) {
			this.model = model;
			return this;
		}

		public Builder tools(List<String> tools) {
			this.tools = tools;
			return this;
		}

		public Builder effort(String effort) {
			this.effort = effort;
			return this;
		}

		public Builder fallbackModels(List<String> fallbackModels) {
			this.fallbackModels = fallbackModels;
			return this;
		}

		public Builder maxBudgetUsd(Double maxBudgetUsd) {
			this.maxBudgetUsd = maxBudgetUsd;
			return this;
		}

		public Builder jsonSchema(String jsonSchema) {
			this.jsonSchema = jsonSchema;
			return this;
		}

		public Builder settingSources(List<String> settingSources) {
			this.settingSources = settingSources;
			return this;
		}

		public Builder extraArgs(List<String> extraArgs) {
			this.extraArgs = extraArgs;
			return this;
		}

		public ClaudeCodeCliRequest build() {
			return new ClaudeCodeCliRequest(this.prompt, this.systemPrompt, this.appendSystemPrompt, this.model,
					this.tools, this.effort, this.fallbackModels, this.maxBudgetUsd, this.jsonSchema,
					this.settingSources, this.extraArgs);
		}

	}

}
