package com.iskeru.springai.claudecode.replay;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import com.iskeru.springai.claudecode.ClaudeCodeException;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;

/**
 * Stores each fixture as one pretty-printed JSON file under a directory, named
 * {@code <prompt-slug>-<key>.json}.
 *
 * <p>
 * A fixture holds both the request that produced it and the CLI's untouched response
 * envelope, so the directory is meant to be committed: fixtures show up in review as
 * readable diffs, and a changed prompt is visible as a new file rather than a silent
 * behaviour change.
 *
 * <pre>{@code
 * {
 *   "key" : "4f1c…",
 *   "request" : { "model" : "sonnet", "systemPrompt" : "…", "prompt" : "What is 2 + 2?", … },
 *   "response" : { "result" : "4", "usage" : { … }, … }
 * }
 * }</pre>
 */
public class FileSystemFixtureStore implements FixtureStore {

	private final Path directory;

	private final ObjectMapper objectMapper;

	/**
	 * Fixture files are named with a slug that the key alone cannot reproduce, so the
	 * resolved path is remembered as fixtures are written and looked up by directory scan
	 * otherwise.
	 */
	private final Map<String, Path> pathsByKey = new ConcurrentHashMap<>();

	public FileSystemFixtureStore(Path directory) {
		this(directory, new ObjectMapper());
	}

	public FileSystemFixtureStore(Path directory, ObjectMapper objectMapper) {
		this.directory = directory.toAbsolutePath().normalize();
		this.objectMapper = objectMapper;
	}

	@Override
	public Optional<ClaudeCodeCliResponse> load(String key) {
		Path file = locate(key);
		if (file == null) {
			return Optional.empty();
		}
		try {
			JsonNode fixture = this.objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
			JsonNode response = fixture.get("response");
			if (response == null || response.isNull()) {
				throw new ClaudeCodeException("Fixture %s has no `response` field".formatted(file));
			}
			return Optional.of(ClaudeCodeCliResponse.fromJson(this.objectMapper.writeValueAsString(response),
					this.objectMapper));
		}
		catch (IOException ex) {
			throw new ClaudeCodeException("Could not read fixture " + file, ex);
		}
	}

	@Override
	public void save(String key, ClaudeCodeCliRequest request, ClaudeCodeCliResponse response) {
		Path file = this.directory.resolve(FixtureKey.fileName(request, key));
		try {
			Files.createDirectories(this.directory);

			ObjectNode fixture = this.objectMapper.createObjectNode();
			fixture.put("key", key);
			fixture.set("request", this.objectMapper.valueToTree(describeRequest(request)));
			fixture.set("response", this.objectMapper.readTree(response.rawJson()));

			// Write-then-move so a crash mid-write cannot leave a truncated fixture that
			// would later replay as a corrupt response.
			Path temp = Files.createTempFile(this.directory, file.getFileName().toString(), ".tmp");
			Files.writeString(temp, this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(fixture),
					StandardCharsets.UTF_8);
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);

			// A key can only ever map to one slug, so stale entries are impossible here.
			this.pathsByKey.put(key, file);
		}
		catch (IOException ex) {
			throw new ClaudeCodeException("Could not write fixture " + file, ex);
		}
	}

	@Override
	public String describe() {
		return this.directory.toString();
	}

	/** The directory fixtures are read from and written to. */
	public Path getDirectory() {
		return this.directory;
	}

	private Path locate(String key) {
		Path known = this.pathsByKey.get(key);
		if (known != null && Files.isRegularFile(known)) {
			return known;
		}
		if (!Files.isDirectory(this.directory)) {
			return null;
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(this.directory, "*" + key + ".json")) {
			for (Path entry : entries) {
				this.pathsByKey.put(key, entry);
				return entry;
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not scan fixture directory " + this.directory, ex);
		}
		return null;
	}

	/**
	 * The request fields that {@link FixtureKey} hashes, in a shape that reads well in a
	 * diff. Fields that do not affect the key are left out so a fixture never looks stale
	 * for a reason that does not matter.
	 */
	private Map<String, Object> describeRequest(ClaudeCodeCliRequest request) {
		Map<String, Object> described = new LinkedHashMap<>();
		described.put("model", request.model());
		described.put("systemPrompt", request.systemPrompt());
		described.put("appendSystemPrompt", request.appendSystemPrompt());
		described.put("prompt", request.prompt());
		described.put("tools", request.tools());
		described.put("effort", request.effort());
		described.put("fallbackModels", request.fallbackModels());
		described.put("jsonSchema", request.jsonSchema());
		described.put("settingSources", request.settingSources());
		described.put("extraArgs", request.extraArgs());
		return described;
	}

}
