package com.iskeru.springai.claudecode.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A {@link ClaudeCodeCli} test double: captures the requests it is given and answers from
 * a canned JSON envelope.
 */
public class RecordingFakeCli implements ClaudeCodeCli {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	private final List<ClaudeCodeCliRequest> requests = new ArrayList<>();

	private final Function<ClaudeCodeCliRequest, String> responder;

	public RecordingFakeCli() {
		this(request -> envelope("ok"));
	}

	public RecordingFakeCli(Function<ClaudeCodeCliRequest, String> responder) {
		this.responder = responder;
	}

	@Override
	public ClaudeCodeCliResponse execute(ClaudeCodeCliRequest request) {
		this.requests.add(request);
		return ClaudeCodeCliResponse.fromJson(this.responder.apply(request), OBJECT_MAPPER);
	}

	public List<ClaudeCodeCliRequest> getRequests() {
		return this.requests;
	}

	public ClaudeCodeCliRequest getOnlyRequest() {
		if (this.requests.size() != 1) {
			throw new AssertionError("Expected exactly one CLI invocation but saw " + this.requests.size());
		}
		return this.requests.get(0);
	}

	public int getInvocationCount() {
		return this.requests.size();
	}

	/** A minimal but realistic `claude -p --output-format json` envelope. */
	public static String envelope(String result) {
		return """
				{
				  "type": "result",
				  "subtype": "success",
				  "is_error": false,
				  "stop_reason": "end_turn",
				  "num_turns": 1,
				  "duration_ms": 1766,
				  "session_id": "11111111-2222-4333-8444-555555555555",
				  "total_cost_usd": 0.0486657,
				  "usage": {
				    "input_tokens": 185,
				    "output_tokens": 5,
				    "cache_read_input_tokens": 3289,
				    "cache_creation_input_tokens": 7933
				  },
				  "modelUsage": { "claude-sonnet-5": { "inputTokens": 185, "outputTokens": 5 } },
				  "result": "%s"
				}""".formatted(result);
	}

}
