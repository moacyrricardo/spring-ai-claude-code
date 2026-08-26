package com.iskeru.springai.claudecode.cli;

import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.iskeru.springai.claudecode.ClaudeCodeException;

/**
 * The parsed {@code claude -p --output-format json} result envelope.
 *
 * <p>
 * {@link #rawJson()} is retained verbatim because it is what the record/replay layer
 * persists: a fixture stores the original CLI payload, so replaying reconstructs exactly
 * the same response object that the live run produced.
 *
 * @param result the assistant's final text
 * @param sessionId the CLI session id, surfaced as the Spring AI response id
 * @param model the canonical model that served the request, e.g. {@code claude-sonnet-5}
 * @param stopReason e.g. {@code end_turn}, surfaced as the Spring AI finish reason
 * @param error whether the CLI flagged the turn as failed
 * @param apiErrorStatus the upstream API error status, when there was one
 * @param numTurns how many assistant turns the CLI took
 * @param durationMs wall-clock duration reported by the CLI
 * @param totalCostUsd what the turn cost, as computed by the CLI
 * @param usage token accounting for the turn
 * @param rawJson the untouched CLI payload
 * @param replayed whether this came from a fixture rather than a live invocation; the
 * usage and cost figures of a replayed response describe the original recording, not this
 * run, which cost nothing
 */
public record ClaudeCodeCliResponse(String result, String sessionId, String model, String stopReason, boolean error,
		String apiErrorStatus, Integer numTurns, Long durationMs, Double totalCostUsd, Usage usage, String rawJson,
		boolean replayed) {

	/**
	 * Token counts for a single turn.
	 *
	 * @param inputTokens uncached input tokens
	 * @param outputTokens generated tokens
	 * @param cacheReadInputTokens tokens served from the prompt cache
	 * @param cacheCreationInputTokens tokens written into the prompt cache
	 */
	public record Usage(Integer inputTokens, Integer outputTokens, Long cacheReadInputTokens,
			Long cacheCreationInputTokens) {

		public static final Usage EMPTY = new Usage(0, 0, 0L, 0L);

	}

	/**
	 * Parses a CLI result envelope.
	 * @param json the raw stdout of a {@code --output-format json} invocation
	 * @param objectMapper the mapper to parse with
	 * @throws ClaudeCodeException if the payload is not the expected envelope
	 */
	public static ClaudeCodeCliResponse fromJson(String json, ObjectMapper objectMapper) {
		JsonNode root;
		try {
			root = objectMapper.readTree(json);
		}
		catch (Exception ex) {
			throw new ClaudeCodeException("Could not parse `claude -p --output-format json` output: " + preview(json),
					ex);
		}
		if (root == null || !root.isObject()) {
			throw new ClaudeCodeException(
					"Expected a JSON object from `claude -p --output-format json` but got: " + preview(json));
		}
		if (!root.has("result")) {
			throw new ClaudeCodeException("`claude -p` output has no `result` field. Output was: " + preview(json));
		}

		JsonNode usageNode = root.path("usage");
		Usage usage = usageNode.isObject()
				? new Usage(intOrNull(usageNode, "input_tokens"), intOrNull(usageNode, "output_tokens"),
						longOrNull(usageNode, "cache_read_input_tokens"),
						longOrNull(usageNode, "cache_creation_input_tokens"))
				: Usage.EMPTY;

		return new ClaudeCodeCliResponse(root.path("result").asText(""), textOrNull(root, "session_id"),
				canonicalModel(root), textOrNull(root, "stop_reason"), root.path("is_error").asBoolean(false),
				textOrNull(root, "api_error_status"), intOrNull(root, "num_turns"), longOrNull(root, "duration_ms"),
				root.hasNonNull("total_cost_usd") ? root.path("total_cost_usd").asDouble() : null, usage, json, false);
	}

	/** Returns this response marked as having been served from a fixture. */
	public ClaudeCodeCliResponse asReplayed() {
		return this.replayed ? this
				: new ClaudeCodeCliResponse(this.result, this.sessionId, this.model, this.stopReason, this.error,
						this.apiErrorStatus, this.numTurns, this.durationMs, this.totalCostUsd, this.usage,
						this.rawJson, true);
	}

	/**
	 * The CLI reports the model under {@code modelUsage}, keyed by model id. A single-turn
	 * request has exactly one key; if a fallback kicked in mid-turn there may be more, in
	 * which case the first one is reported.
	 */
	private static String canonicalModel(JsonNode root) {
		JsonNode modelUsage = root.path("modelUsage");
		if (modelUsage.isObject()) {
			Iterator<String> names = modelUsage.fieldNames();
			if (names.hasNext()) {
				return names.next();
			}
		}
		return textOrNull(root, "model");
	}

	private static String textOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asText();
	}

	private static Integer intOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asInt();
	}

	private static Long longOrNull(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return (value == null || value.isNull()) ? null : value.asLong();
	}

	private static String preview(String json) {
		if (json == null) {
			return "<null>";
		}
		String trimmed = json.strip();
		return (trimmed.length() <= 500) ? trimmed : trimmed.substring(0, 500) + "… (truncated)";
	}

}
