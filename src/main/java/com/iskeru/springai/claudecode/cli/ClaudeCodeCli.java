package com.iskeru.springai.claudecode.cli;

/**
 * Executes a single {@code claude -p} invocation.
 *
 * <p>
 * The seam that record/replay plugs into: {@link ProcessClaudeCodeCli} shells out to the
 * real binary, and
 * {@link com.iskeru.springai.claudecode.replay.ReplayingClaudeCodeCli} decorates it to
 * serve responses from disk.
 */
@FunctionalInterface
public interface ClaudeCodeCli {

	/**
	 * @throws com.iskeru.springai.claudecode.ClaudeCodeException if the invocation fails
	 */
	ClaudeCodeCliResponse execute(ClaudeCodeCliRequest request);

}
