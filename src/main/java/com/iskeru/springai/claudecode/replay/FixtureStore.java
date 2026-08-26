package com.iskeru.springai.claudecode.replay;

import java.util.Optional;

import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;

/**
 * Persistence for recorded CLI responses, keyed by {@link FixtureKey}.
 */
public interface FixtureStore {

	/**
	 * @param key the request's fixture key
	 * @return the recorded response, or empty on a miss
	 */
	Optional<ClaudeCodeCliResponse> load(String key);

	/**
	 * Records a response, replacing any existing fixture for the same key. The request is
	 * passed so implementations can store it alongside the response for reviewability.
	 */
	void save(String key, ClaudeCodeCliRequest request, ClaudeCodeCliResponse response);

	/**
	 * A human-readable description of where fixtures live, used in miss messages.
	 */
	String describe();

}
