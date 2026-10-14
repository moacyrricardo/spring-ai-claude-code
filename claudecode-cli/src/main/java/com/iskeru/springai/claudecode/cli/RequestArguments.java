package com.iskeru.springai.claudecode.cli;

import java.util.List;
import java.util.Set;

import com.iskeru.springai.claudecode.ClaudeCodeException;

/** Validate transport ownership before either live execution or fixture lookup. */
final class RequestArguments {

	private static final Set<String> RESERVED = Set.of("--print", "-p", "--output-format", "--input-format",
			"--verbose", "--include-partial-messages", "--replay-user-messages", "--continue", "-c", "--resume",
			"-r", "--session-id", "--fork-session", "--no-session-persistence", "--background", "--bg", "--cloud",
			"--remote-control", "--teleport", "--from-pr", "--help", "-h", "--version", "-v", "--");

	private RequestArguments() {
	}

	static void validate(List<String> arguments, IsolationPolicy isolation) {
		for (int i = 0; i < arguments.size(); i++) {
			String argument = arguments.get(i);
			if (argument.indexOf('\0') >= 0) {
				throw new ClaudeCodeException("CLI arguments cannot contain NUL characters");
			}
			String flag = argument.split("=", 2)[0];
			if (RESERVED.contains(flag)) {
				throw new ClaudeCodeException("CLI flag " + flag + " is owned by the transport and cannot be overridden");
			}
			if (isolation == IsolationPolicy.CUSTOM) {
				continue;
			}
			// A deliberately small allowlist. Add flags here only with a semantic review.
			if (!flag.equals("--max-turns")) {
				throw new ClaudeCodeException("CLI argument " + flag + " is not allowed in ISOLATED mode; use CUSTOM "
						+ "for intentional tools/settings or other CLI customizations");
			}
			String value;
			if (argument.contains("=")) {
				value = argument.substring(argument.indexOf('=') + 1);
			}
			else {
				value = ++i < arguments.size() ? arguments.get(i) : "";
			}
			try {
				if (!value.matches("[0-9]+") || Integer.parseInt(value) <= 0) {
					throw new NumberFormatException();
				}
			}
			catch (NumberFormatException ex) {
				throw new ClaudeCodeException("--max-turns requires a positive integer");
			}
		}
	}

}
