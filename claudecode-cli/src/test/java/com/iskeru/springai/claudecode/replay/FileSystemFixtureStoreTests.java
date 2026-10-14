package com.iskeru.springai.claudecode.replay;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.iskeru.springai.claudecode.ClaudeCodeException;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;
import com.iskeru.springai.claudecode.cli.RecordingFakeCli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileSystemFixtureStoreTests {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@TempDir
	Path fixtures;

	private final ClaudeCodeCliRequest request = ClaudeCodeCliRequest.builder()
		.prompt("What is 2 + 2?")
		.model("sonnet")
		.tools(List.of())
		.build();

	private FileSystemFixtureStore store() {
		return new FileSystemFixtureStore(this.fixtures, OBJECT_MAPPER);
	}

	private static ClaudeCodeCliResponse response(String result) {
		return ClaudeCodeCliResponse.fromJson(RecordingFakeCli.envelope(result), OBJECT_MAPPER);
	}

	@Test
	void aSavedResponseRoundTrips() {
		FileSystemFixtureStore store = store();
		String key = FixtureKey.of(this.request);

		store.save(key, this.request, response("4"));

		ClaudeCodeCliResponse loaded = store.load(key).orElseThrow();
		assertThat(loaded.result()).isEqualTo("4");
		assertThat(loaded.sessionId()).isEqualTo("11111111-2222-4333-8444-555555555555");
		assertThat(loaded.usage().inputTokens()).isEqualTo(185);
	}

	@Test
	void aFixtureIsFoundByAFreshStoreInstance() {
		String key = FixtureKey.of(this.request);
		store().save(key, this.request, response("4"));

		assertThat(store().load(key)).as("CI reads fixtures it did not write").isPresent();
	}

	@Test
	void anUnknownKeyIsAMiss() {
		assertThat(store().load("0123456789abcdef0123456789abcdef")).isEmpty();
	}

	@Test
	void aMissingDirectoryIsAMissRatherThanAnError() {
		FileSystemFixtureStore store = new FileSystemFixtureStore(this.fixtures.resolve("not-created-yet"),
				OBJECT_MAPPER);

		assertThat(store.load("0123456789abcdef0123456789abcdef")).isEmpty();
	}

	@Test
	void theDirectoryIsCreatedOnFirstWrite() {
		Path nested = this.fixtures.resolve("a/b/c");
		FileSystemFixtureStore store = new FileSystemFixtureStore(nested, OBJECT_MAPPER);

		store.save(FixtureKey.of(this.request), this.request, response("4"));

		assertThat(nested).isDirectory();
	}

	@Test
	void theFixtureFileIsReadableAndCarriesTheRequest() throws Exception {
		String key = FixtureKey.of(this.request);
		store().save(key, this.request, response("4"));

		Path file = this.fixtures.resolve(FixtureKey.fileName(this.request, key));
		String contents = Files.readString(file);

		assertThat(contents).contains("\"key\" : \"" + key + "\"")
			.contains("\"prompt\" : \"What is 2 + 2?\"")
			.contains("\"model\" : \"sonnet\"")
			.contains("\"result\" : \"4\"");
	}

	@Test
	void reRecordingReplacesTheExistingFixture() {
		FileSystemFixtureStore store = store();
		String key = FixtureKey.of(this.request);

		store.save(key, this.request, response("4"));
		store.save(key, this.request, response("four"));

		assertThat(store.load(key).orElseThrow().result()).isEqualTo("four");
		assertThat(this.fixtures).isDirectoryContaining(path -> path.getFileName().toString().endsWith(key + ".json"));
	}

	@Test
	void noTemporaryFilesAreLeftBehind() {
		store().save(FixtureKey.of(this.request), this.request, response("4"));

		assertThat(this.fixtures).isDirectoryNotContaining(path -> path.getFileName().toString().endsWith(".tmp"));
	}

	@Test
	void aMalformedFixtureFailsLoudly() throws Exception {
		String key = FixtureKey.of(this.request);
		Files.writeString(this.fixtures.resolve(FixtureKey.fileName(this.request, key)), "{\"key\":\"" + key + "\"}");

		assertThatThrownBy(() -> store().load(key)).isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("no `response` field");
	}

}
