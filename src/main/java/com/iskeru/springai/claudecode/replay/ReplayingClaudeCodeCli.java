package com.iskeru.springai.claudecode.replay;

import java.util.Optional;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import com.iskeru.springai.claudecode.ClaudeCodeException;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCli;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;

/**
 * Wraps a {@link ClaudeCodeCli} with record/replay against a {@link FixtureStore}.
 *
 * <p>
 * Recording once and replaying thereafter is what makes a model-backed test suite viable
 * in CI: the run is offline, deterministic, and costs nothing, and a prompt change is
 * caught as a fixture miss instead of quietly turning into a live call.
 *
 * <pre>{@code
 * ClaudeCodeCli cli = ReplayingClaudeCodeCli.builder()
 *     .delegate(ProcessClaudeCodeCli.builder().build())
 *     .store(new FileSystemFixtureStore(Path.of("src/test/resources/claude-fixtures")))
 *     .mode(ReplayMode.AUTO)
 *     .build();
 * }</pre>
 *
 * @see ReplayMode
 */
public class ReplayingClaudeCodeCli implements ClaudeCodeCli {

	private static final Log logger = LogFactory.getLog(ReplayingClaudeCodeCli.class);

	private final ClaudeCodeCli delegate;

	private final FixtureStore store;

	private final ReplayMode mode;

	protected ReplayingClaudeCodeCli(Builder builder) {
		this.delegate = builder.delegate;
		this.store = builder.store;
		this.mode = builder.mode;
	}

	public static Builder builder() {
		return new Builder();
	}

	@Override
	public ClaudeCodeCliResponse execute(ClaudeCodeCliRequest request) {
		if (this.mode == ReplayMode.LIVE) {
			return this.delegate.execute(request);
		}

		String key = FixtureKey.of(request);

		if (this.mode == ReplayMode.REPLAY || this.mode == ReplayMode.AUTO) {
			Optional<ClaudeCodeCliResponse> recorded = this.store.load(key);
			if (recorded.isPresent()) {
				logger.debug("Replaying Claude Code fixture " + key);
				return recorded.get().asReplayed();
			}
			if (this.mode == ReplayMode.REPLAY) {
				throw new ClaudeCodeException(missMessage(key, request));
			}
		}

		if (this.delegate == null) {
			throw new ClaudeCodeException(
					"No fixture for key %s and no CLI delegate configured to record one.".formatted(key));
		}

		ClaudeCodeCliResponse response = this.delegate.execute(request);
		this.store.save(key, request, response);
		logger.info("Recorded Claude Code fixture " + key);
		return response;
	}

	/** The mode this instance is operating in. */
	public ReplayMode getMode() {
		return this.mode;
	}

	private String missMessage(String key, ClaudeCodeCliRequest request) {
		return """
				No recorded Claude Code fixture for key %s, and replay mode is REPLAY so the CLI will not be called.

				Fixture directory: %s
				Model:  %s
				Prompt: %s

				Record it by re-running with the replay mode set to RECORD or AUTO \
				(property: spring.ai.claude-code.replay.mode), then commit the new fixture file.\
				""".formatted(key, this.store.describe(), request.model(), abbreviate(request.prompt()));
	}

	private static String abbreviate(String text) {
		if (text == null) {
			return "<none>";
		}
		String single = text.strip().replaceAll("\\s+", " ");
		return (single.length() <= 200) ? single : single.substring(0, 200) + "…";
	}

	public static final class Builder {

		private ClaudeCodeCli delegate;

		private FixtureStore store;

		private ReplayMode mode = ReplayMode.LIVE;

		private Builder() {
		}

		/**
		 * The CLI to call when a fixture is not being used. Optional in
		 * {@link ReplayMode#REPLAY}, where it is never consulted.
		 */
		public Builder delegate(ClaudeCodeCli delegate) {
			this.delegate = delegate;
			return this;
		}

		public Builder store(FixtureStore store) {
			this.store = store;
			return this;
		}

		public Builder mode(ReplayMode mode) {
			this.mode = mode;
			return this;
		}

		public ReplayingClaudeCodeCli build() {
			if (this.mode == null) {
				throw new IllegalStateException("A ReplayMode must be provided");
			}
			if (this.store == null) {
				throw new IllegalStateException("A FixtureStore must be provided");
			}
			if (this.delegate == null && this.mode != ReplayMode.REPLAY) {
				throw new IllegalStateException(
						"A ClaudeCodeCli delegate is required in %s mode; only REPLAY can run without one."
							.formatted(this.mode));
			}
			return new ReplayingClaudeCodeCli(this);
		}

	}

}
