package com.iskeru.springai.claudecode.autoconfigure;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each adapter module ships its own copy of the Boot auto-configuration registration file,
 * because module resources — unlike the adapter's Java sources — are not shared. A copy that
 * is missing, misspelled or left behind after a rename would leave Boot silently
 * contributing no beans, which no {@code ApplicationContextRunner} test would notice: those
 * register the configuration class explicitly.
 */
class AutoConfigurationImportsTests {

	private static final String IMPORTS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

	@Test
	void registersTheAutoConfigurationForThisModulesBootGeneration() throws Exception {
		try (InputStream in = getClass().getClassLoader().getResourceAsStream(IMPORTS)) {
			assertThat(in).as("%s on the classpath", IMPORTS).isNotNull();

			List<String> entries = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines()
				.map(String::trim)
				.filter(line -> !line.isEmpty() && !line.startsWith("#"))
				.collect(Collectors.toList());

			assertThat(entries).containsExactly(ClaudeCodeChatAutoConfiguration.class.getName());
			assertThat(Class.forName(entries.get(0))).isEqualTo(ClaudeCodeChatAutoConfiguration.class);
		}
	}

}
