package com.iskeru.springai.claudecode.autoconfigure;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import com.iskeru.springai.claudecode.ClaudeCodeChatOptions;
import com.iskeru.springai.claudecode.replay.ReplayMode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration for the Claude Code {@code ChatModel}.
 *
 * <pre>{@code
 * spring.ai.claude-code.options.model=sonnet
 * spring.ai.claude-code.replay.mode=auto
 * spring.ai.claude-code.replay.directory=src/test/resources/claude-fixtures
 * }</pre>
 */
@ConfigurationProperties(ClaudeCodeChatProperties.PREFIX)
public class ClaudeCodeChatProperties {

	public static final String PREFIX = "spring.ai.claude-code";

	/** Whether to auto-configure the Claude Code chat model. */
	private boolean enabled = true;

	/** Path to, or name of, the Claude Code binary. Resolved on PATH when not absolute. */
	private String executable = "claude";

	/** Maximum wall-clock time for a single CLI invocation. */
	private Duration timeout = Duration.ofMinutes(5);

	/** Directory the CLI process runs in. Defaults to the JVM's working directory. */
	private Path workingDirectory;

	/**
	 * Whether the CLI may persist sessions to disk. Off by default so test runs leave no
	 * session files behind.
	 */
	private boolean sessionPersistence = false;

	/** Extra environment variables for the CLI process. */
	private Map<String, String> environment = new LinkedHashMap<>();

	/** Default chat options applied to every request. */
	@NestedConfigurationProperty
	private ClaudeCodeChatOptions options = new ClaudeCodeChatOptions();

	@NestedConfigurationProperty
	private Replay replay = new Replay();

	public boolean isEnabled() {
		return this.enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getExecutable() {
		return this.executable;
	}

	public void setExecutable(String executable) {
		this.executable = executable;
	}

	public Duration getTimeout() {
		return this.timeout;
	}

	public void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	public Path getWorkingDirectory() {
		return this.workingDirectory;
	}

	public void setWorkingDirectory(Path workingDirectory) {
		this.workingDirectory = workingDirectory;
	}

	public boolean isSessionPersistence() {
		return this.sessionPersistence;
	}

	public void setSessionPersistence(boolean sessionPersistence) {
		this.sessionPersistence = sessionPersistence;
	}

	public Map<String, String> getEnvironment() {
		return this.environment;
	}

	public void setEnvironment(Map<String, String> environment) {
		this.environment = environment;
	}

	public ClaudeCodeChatOptions getOptions() {
		return this.options;
	}

	public void setOptions(ClaudeCodeChatOptions options) {
		this.options = options;
	}

	public Replay getReplay() {
		return this.replay;
	}

	public void setReplay(Replay replay) {
		this.replay = replay;
	}

	/**
	 * Record/replay settings. Leaving {@code mode} at {@link ReplayMode#LIVE} calls the
	 * CLI on every request; any other mode routes requests through the fixture directory.
	 */
	public static class Replay {

		/**
		 * How fixtures are used: live (always call the CLI), record (always call and
		 * overwrite fixtures), replay (fixtures only, fail on a miss), or auto (replay
		 * when a fixture exists, otherwise call and record).
		 */
		private ReplayMode mode = ReplayMode.LIVE;

		/** Directory holding recorded fixtures. */
		private Path directory = Path.of("src", "test", "resources", "claude-fixtures");

		public ReplayMode getMode() {
			return this.mode;
		}

		public void setMode(ReplayMode mode) {
			this.mode = mode;
		}

		public Path getDirectory() {
			return this.directory;
		}

		public void setDirectory(Path directory) {
			this.directory = directory;
		}

	}

}
