package com.iskeru.springai.claudecode.replay;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;

import static org.assertj.core.api.Assertions.assertThat;

class FixtureKeyTests {

	private static ClaudeCodeCliRequest.Builder request() {
		return ClaudeCodeCliRequest.builder().prompt("What is 2 + 2?").model("sonnet").systemPrompt("Be terse.");
	}

	@Test
	void theSameRequestAlwaysYieldsTheSameKey() {
		assertThat(FixtureKey.of(request().build())).isEqualTo(FixtureKey.of(request().build()));
	}

	@Test
	void theKeyIsStableAcrossVersions() {
		// Pinned deliberately: a change here silently invalidates every committed fixture,
		// so it should be a conscious decision with a re-record, not a refactoring
		// side effect.
		assertThat(FixtureKey.of(request().build())).isEqualTo("ae2bb972facd570fbe90051a47b8b5ac");
	}

	@Test
	void aDifferentPromptYieldsADifferentKey() {
		assertThat(FixtureKey.of(request().prompt("What is 3 + 3?").build()))
			.isNotEqualTo(FixtureKey.of(request().build()));
	}

	@Test
	void aDifferentModelYieldsADifferentKey() {
		assertThat(FixtureKey.of(request().model("opus").build())).isNotEqualTo(FixtureKey.of(request().build()));
	}

	@Test
	void aDifferentSystemPromptYieldsADifferentKey() {
		assertThat(FixtureKey.of(request().systemPrompt("Be verbose.").build()))
			.isNotEqualTo(FixtureKey.of(request().build()));
	}

	@Test
	void enablingToolsYieldsADifferentKey() {
		assertThat(FixtureKey.of(request().tools(List.of("Read")).build()))
			.isNotEqualTo(FixtureKey.of(request().tools(List.of()).build()));
	}

	@Test
	void theSpendGuardDoesNotAffectTheKey() {
		assertThat(FixtureKey.of(request().maxBudgetUsd(0.5).build())).isEqualTo(FixtureKey.of(request().build()));
	}

	@Test
	void fieldsCannotBeShiftedAcrossTheirBoundaries() {
		String separate = FixtureKey.of(ClaudeCodeCliRequest.builder().prompt("ab").systemPrompt("c").build());
		String shifted = FixtureKey.of(ClaudeCodeCliRequest.builder().prompt("a").systemPrompt("bc").build());

		assertThat(separate).isNotEqualTo(shifted);
	}

	@Test
	void anUnsetFieldIsDistinctFromAnEmptyOne() {
		String unset = FixtureKey.of(ClaudeCodeCliRequest.builder().prompt("hi").systemPrompt(null).build());
		String empty = FixtureKey.of(ClaudeCodeCliRequest.builder().prompt("hi").systemPrompt("").build());

		assertThat(unset).isNotEqualTo(empty);
	}

	@Test
	void theFileNameCarriesAReadableSlug() {
		String key = FixtureKey.of(request().build());

		assertThat(FixtureKey.fileName(request().build(), key)).isEqualTo("what-is-2-2-" + key + ".json");
	}

	@Test
	void theFileNameStaysBoundedForLongPrompts() {
		ClaudeCodeCliRequest longPrompt = request().prompt("word ".repeat(200)).build();
		String key = FixtureKey.of(longPrompt);

		String fileName = FixtureKey.fileName(longPrompt, key);

		assertThat(fileName).hasSizeLessThan(100).endsWith(key + ".json").doesNotContain("--");
	}

	@Test
	void aPromptWithNoAlphanumericsStillProducesAValidFileName() {
		ClaudeCodeCliRequest symbols = ClaudeCodeCliRequest.builder().prompt("!!! ???").build();
		String key = FixtureKey.of(symbols);

		assertThat(FixtureKey.fileName(symbols, key)).isEqualTo(key + ".json");
	}

}
