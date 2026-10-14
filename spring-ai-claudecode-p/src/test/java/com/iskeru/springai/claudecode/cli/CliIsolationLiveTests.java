package com.iskeru.springai.claudecode.cli;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Inspects initialization using the actual default command, with diagnostic NDJSON output. */
@Tag("live")
class CliIsolationLiveTests {

	@Test
	void defaultCompletionLoadsNoExternalToolsMcpServersOrSkills() throws Exception {
		var cli = new InspectableCli();
		System.out.println("Isolation compatibility: " + cli.verifyVersion());
		var request = ClaudeCodeCliRequest.builder().prompt("Reply exactly PONG").model("sonnet").build();
		var command = cli.command(request);
		command.set(command.indexOf("--output-format") + 1, "stream-json");
		command.add("--verbose");
		var output = ProcessRunner.run(new ProcessBuilder(command).start(), request.prompt(), Duration.ofMinutes(2),
				1024 * 1024, 64 * 1024);
		assertThat(output.exitCode()).as(output.stderr()).isZero();
		JsonNode init = null;
		JsonNode result = null;
		var mapper = new ObjectMapper();
		for (String line : output.stdout().lines().toList()) {
			var event = mapper.readTree(line);
			if ("system".equals(event.path("type").asText()) && "init".equals(event.path("subtype").asText())) init = event;
			if ("result".equals(event.path("type").asText())) result = event;
		}
		assertThat(init).isNotNull();
		for (String field : List.of("tools", "mcp_servers", "skills")) {
			assertThat(init.path(field).isArray()).as(field + " is reported").isTrue();
			assertThat(init.path(field).size()).as(field).isZero();
		}
		assertThat(result).isNotNull();
		assertThat(result.path("is_error").asBoolean()).isFalse();
		assertThat(result.path("result").asText().strip()).isEqualTo("PONG");
	}

	private static class InspectableCli extends ProcessClaudeCodeCli {
		InspectableCli() { super(ProcessClaudeCodeCli.builder()); }
		List<String> command(ClaudeCodeCliRequest request) { return buildCommand(request); }
	}

}
