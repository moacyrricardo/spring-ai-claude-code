package com.iskeru.springai.claudecode;

/**
 * Thrown when the {@code claude} CLI cannot be invoked, times out, exits non-zero, or
 * returns a payload that cannot be understood.
 */
public class ClaudeCodeException extends RuntimeException {

	public ClaudeCodeException(String message) {
		super(message);
	}

	public ClaudeCodeException(String message, Throwable cause) {
		super(message, cause);
	}

}
