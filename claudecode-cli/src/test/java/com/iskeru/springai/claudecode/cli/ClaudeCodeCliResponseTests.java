package com.iskeru.springai.claudecode.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import com.iskeru.springai.claudecode.ClaudeCodeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClaudeCodeCliResponseTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void parsesTheResultEnvelope() {
		ClaudeCodeCliResponse response = ClaudeCodeCliResponse.fromJson(RecordingFakeCli.envelope("PONG"),
				this.objectMapper);

		assertThat(response.result()).isEqualTo("PONG");
		assertThat(response.sessionId()).isEqualTo("11111111-2222-4333-8444-555555555555");
		assertThat(response.stopReason()).isEqualTo("end_turn");
		assertThat(response.error()).isFalse();
		assertThat(response.numTurns()).isEqualTo(1);
		assertThat(response.durationMs()).isEqualTo(1766L);
		assertThat(response.totalCostUsd()).isEqualTo(0.0486657);
		assertThat(response.replayed()).isFalse();
	}

	@Test
	void readsTheCanonicalModelFromModelUsage() {
		ClaudeCodeCliResponse response = ClaudeCodeCliResponse.fromJson(RecordingFakeCli.envelope("ok"),
				this.objectMapper);

		assertThat(response.model()).isEqualTo("claude-sonnet-5");
	}

	@Test
	void parsesUsage() {
		ClaudeCodeCliResponse response = ClaudeCodeCliResponse.fromJson(RecordingFakeCli.envelope("ok"),
				this.objectMapper);

		assertThat(response.usage().inputTokens()).isEqualTo(185);
		assertThat(response.usage().outputTokens()).isEqualTo(5);
		assertThat(response.usage().cacheReadInputTokens()).isEqualTo(3289L);
		assertThat(response.usage().cacheCreationInputTokens()).isEqualTo(7933L);
	}

	@Test
	void retainsTheRawPayloadForFixtureStorage() {
		String json = RecordingFakeCli.envelope("ok");

		assertThat(ClaudeCodeCliResponse.fromJson(json, this.objectMapper).rawJson()).isEqualTo(json);
	}

	@Test
	void toleratesAMissingUsageBlock() {
		ClaudeCodeCliResponse response = ClaudeCodeCliResponse.fromJson("""
				{"result":"ok","is_error":false}""", this.objectMapper);

		assertThat(response.usage()).isEqualTo(ClaudeCodeCliResponse.Usage.EMPTY);
		assertThat(response.model()).isNull();
	}

	@Test
	void surfacesTheErrorFlag() {
		ClaudeCodeCliResponse response = ClaudeCodeCliResponse.fromJson("""
				{"result":"boom","is_error":true,"api_error_status":"529"}""", this.objectMapper);

		assertThat(response.error()).isTrue();
		assertThat(response.apiErrorStatus()).isEqualTo("529");
	}

	@Test
	void rejectsOutputThatIsNotAResultEnvelope() {
		assertThatThrownBy(() -> ClaudeCodeCliResponse.fromJson("{\"type\":\"system\"}", this.objectMapper))
			.isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("no `result` field");
	}

	@Test
	void rejectsNonJsonOutput() {
		assertThatThrownBy(() -> ClaudeCodeCliResponse.fromJson("command not found", this.objectMapper))
			.isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("command not found");
	}

	@Test
	void asReplayedMarksTheResponseWithoutChangingAnythingElse() {
		ClaudeCodeCliResponse response = ClaudeCodeCliResponse.fromJson(RecordingFakeCli.envelope("ok"),
				this.objectMapper);

		ClaudeCodeCliResponse replayed = response.asReplayed();

		assertThat(replayed.replayed()).isTrue();
		assertThat(replayed).usingRecursiveComparison().ignoringFields("replayed").isEqualTo(response);
		assertThat(replayed.asReplayed()).isSameAs(replayed);
	}

}
