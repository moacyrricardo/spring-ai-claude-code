package com.iskeru.springai.claudecode.replay;

/**
 * How {@link ReplayingClaudeCodeCli} treats the fixture store.
 */
public enum ReplayMode {

	/**
	 * Ignore fixtures entirely and always invoke the CLI. The default: a plain live model.
	 */
	LIVE,

	/**
	 * Always invoke the CLI, and write every response to the fixture store, overwriting
	 * whatever was there. Use this to (re)capture fixtures.
	 */
	RECORD,

	/**
	 * Never invoke the CLI. Serve from the fixture store and fail loudly on a miss. This
	 * is the CI mode: offline, deterministic, free, and it turns "someone changed a prompt
	 * without re-recording" into a test failure rather than a silent live call.
	 */
	REPLAY,

	/**
	 * Serve from the fixture store when a fixture exists, otherwise invoke the CLI and
	 * record the result. Convenient locally: new or changed prompts are captured on first
	 * run and are free from then on.
	 */
	AUTO

}
