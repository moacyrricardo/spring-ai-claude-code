package com.iskeru.springai.claudecode.cli;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import com.iskeru.springai.claudecode.ClaudeCodeException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Linux caps a single argv entry at 128 KiB, so a long system prompt has to reach the CLI
 * some other way.
 */
class ArgumentSpillTests {

	private final List<Path> created = new ArrayList<>();

	private static final int THRESHOLD = 1024;

	@AfterEach
	void deleteSpillFiles() throws Exception {
		for (Path file : this.created) {
			Files.deleteIfExists(file);
		}
	}

	@Test
	void leavesACommandThatFitsCompletelyAlone() {
		List<String> command = List.of("claude", "--system-prompt", "short", "--print");

		List<String> result = ArgumentSpill.apply(command, THRESHOLD, this.created);

		assertThat(result).isSameAs(command);
		assertThat(this.created).isEmpty();
	}

	@Test
	void writesAnOversizedSystemPromptToAFileAndSwapsTheFlag() throws Exception {
		String huge = "x".repeat(THRESHOLD * 4);

		List<String> result = ArgumentSpill.apply(List.of("claude", "--system-prompt", huge, "--print"), THRESHOLD,
				this.created);

		assertThat(result).hasSize(4);
		assertThat(result.get(1)).isEqualTo("--system-prompt-file");
		assertThat(result).doesNotContain(huge);
		assertThat(result.get(3)).as("unrelated arguments keep their position").isEqualTo("--print");

		assertThat(this.created).hasSize(1);
		assertThat(Path.of(result.get(2))).isEqualTo(this.created.get(0));
		assertThat(Files.readString(this.created.get(0)))
			.as("the CLI must see byte-identical content")
			.isEqualTo(huge);
	}

	@Test
	void spillsAppendSystemPromptToo() {
		List<String> result = ArgumentSpill.apply(
				List.of("claude", "--append-system-prompt", "y".repeat(THRESHOLD * 2)), THRESHOLD, this.created);

		assertThat(result.get(1)).isEqualTo("--append-system-prompt-file");
	}

	@Test
	void spillsEveryOversizedArgumentInOneCommand() {
		List<String> result = ArgumentSpill.apply(List.of("claude", "--system-prompt", "x".repeat(THRESHOLD * 2),
				"--append-system-prompt", "y".repeat(THRESHOLD * 2)), THRESHOLD, this.created);

		assertThat(result).contains("--system-prompt-file", "--append-system-prompt-file");
		assertThat(this.created).hasSize(2);
	}

	@Test
	@EnabledOnOs({ OS.LINUX, OS.MAC })
	void keepsTheSpillFileReadableOnlyByItsOwner() throws Exception {
		// A system prompt can carry proprietary content; the temp directory is shared.
		ArgumentSpill.apply(List.of("claude", "--system-prompt", "x".repeat(THRESHOLD * 2)), THRESHOLD, this.created);

		assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(this.created.get(0))))
			.isEqualTo("rw-------");
	}

	@Test
	void failsClearlyForAnOversizedArgumentThatHasNoFileForm() {
		// --json-schema has no --json-schema-file counterpart, so there is nowhere to put
		// it; an honest error beats an opaque E2BIG from exec.
		String beyondTheKernelLimit = "x".repeat(ArgumentSpill.MAX_ARGUMENT_BYTES + 1);

		assertThatThrownBy(() -> ArgumentSpill.apply(List.of("claude", "--json-schema", beyondTheKernelLimit),
				THRESHOLD, this.created))
			.isInstanceOf(ClaudeCodeException.class)
			.hasMessageContaining("no file-based form")
			.hasMessageContaining("prompt itself is sent on stdin");
	}

	@Test
	void doesNotTreatATrailingFlagWithNoValueAsSpillable() {
		List<String> command = List.of("claude", "--system-prompt");

		assertThat(ArgumentSpill.apply(command, THRESHOLD, this.created)).isSameAs(command);
	}

	@Test
	void describesArgumentSizesForDiagnostics() {
		String huge = "x".repeat(200_000);

		assertThat(ArgumentSpill.describeSizes(List.of("claude", "--system-prompt", huge)))
			.contains("--system-prompt")
			.contains("200000 bytes");
	}

}
