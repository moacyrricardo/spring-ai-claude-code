package com.iskeru.springai.claudecode.cli;

/** Whether a call uses a controlled completion environment or explicit CLI customizations. */
public enum IsolationPolicy {

	ISOLATED, CUSTOM;

	/** Version the policy when its answer-affecting defaults change. */
	public String fixtureIdentity() {
		return "v1:" + name();
	}

}
