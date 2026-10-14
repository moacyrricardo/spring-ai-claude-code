package com.iskeru.springai.claudecode.cli;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.iskeru.springai.claudecode.ClaudeCodeException;

/** Concurrent, bounded pipe handling shared by version probes and model calls. */
final class ProcessRunner {

	private static final long CLEANUP_NANOS = Duration.ofSeconds(5).toNanos();

	record Result(int exitCode, String stdout, String stderr) {
	}

	private ProcessRunner() {
	}

	static Result run(Process process, String input, Duration timeout, int stdoutLimit, int stderrLimit) {
		long deadline = System.nanoTime() + timeout.toNanos();
		ByteArrayOutputStream stdout = new ByteArrayOutputStream();
		Tail stderr = new Tail(stderrLimit);
		AtomicReference<IOException> outputFailure = new AtomicReference<>();
		AtomicReference<IOException> inputFailure = new AtomicReference<>();
		List<Thread> pumps = List.of(
				daemon("claude-cli-stdin", () -> {
					try (var stream = process.getOutputStream()) {
						stream.write(input.getBytes(StandardCharsets.UTF_8));
					}
					catch (IOException ex) {
						inputFailure.set(ex);
					}
				}),
				daemon("claude-cli-stdout", () -> {
					try (var stream = process.getInputStream()) {
						readStdout(stream, stdout, stdoutLimit);
					}
					catch (IOException ex) {
						outputFailure.compareAndSet(null, ex);
					}
				}),
				daemon("claude-cli-stderr", () -> {
					try (var stream = process.getErrorStream()) {
						byte[] buffer = new byte[8192];
						int count;
						while ((count = stream.read(buffer)) != -1) {
							stderr.append(buffer, count);
						}
					}
					catch (IOException ex) {
						outputFailure.compareAndSet(null, ex);
					}
				}));
		Map<Long, ProcessHandle> descendants = new LinkedHashMap<>();
		ClaudeCodeException primary = null;
		try {
			while (true) {
				process.descendants().forEach(child -> descendants.put(child.pid(), child));
				IOException failure = outputFailure.get();
				if (failure != null) {
					throw new ClaudeCodeException("Failed to read the Claude Code CLI output: " + failure.getMessage(), failure);
				}
				if (inputFailure.get() != null && process.isAlive()) {
					throw new ClaudeCodeException("Failed to write the prompt to the Claude Code CLI's stdin", inputFailure.get());
				}
				if (!process.isAlive() && pumps.stream().noneMatch(Thread::isAlive)) {
					break;
				}
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0) {
					throw new ClaudeCodeException("The Claude Code CLI did not finish within " + timeout);
				}
				TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
			}
			if (outputFailure.get() != null) {
				throw new ClaudeCodeException("Failed to read the Claude Code CLI output: "
						+ outputFailure.get().getMessage(), outputFailure.get());
			}
			if (process.exitValue() == 0 && inputFailure.get() != null) {
				throw new ClaudeCodeException("Failed to write the prompt to the Claude Code CLI's stdin", inputFailure.get());
			}
			return new Result(process.exitValue(), stdout.toString(StandardCharsets.UTF_8), stderr.text());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			primary = new ClaudeCodeException("Interrupted while waiting for the Claude Code CLI", ex);
			throw primary;
		}
		catch (ClaudeCodeException ex) {
			primary = ex;
			throw ex;
		}
		finally {
			if (primary != null) {
				String diagnostics = stderr.text();
				if (!diagnostics.isBlank()) {
					primary.addSuppressed(new IOException("CLI stderr: " + diagnostics));
				}
			}
			cleanup(process, descendants, pumps, primary);
			if (primary != null) {
				for (IOException failure : new IOException[] { outputFailure.get(), inputFailure.get() }) {
					if (failure != null && failure != primary.getCause()) {
						primary.addSuppressed(failure);
					}
				}
			}
		}
	}

	static void readStdout(InputStream stream, ByteArrayOutputStream target, int limit) throws IOException {
		byte[] buffer = new byte[Math.min(limit, 8192)];
		int count;
		while ((count = stream.read(buffer)) != -1) {
			if (count > limit - target.size()) {
				throw new IOException("stdout exceeds the " + limit + "-byte limit");
			}
			target.write(buffer, 0, count);
		}
	}

	private static void cleanup(Process process, Map<Long, ProcessHandle> known, List<Thread> pumps,
			ClaudeCodeException primary) {
		boolean interrupted = Thread.interrupted();
		long deadline = System.nanoTime() + CLEANUP_NANOS;
		List<Thread> workers = new ArrayList<>(pumps);
		try {
			process.descendants().forEach(child -> known.put(child.pid(), child));
			List<ProcessHandle> children = new ArrayList<>(known.values());
			// Let the parent reap its children before stopping it (important for shell wrappers).
			for (ProcessHandle child : children) {
				if (child.isAlive()) {
					child.destroy();
				}
			}
			long politeDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));
			while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < politeDeadline) {
				interrupted |= pause();
			}
			for (ProcessHandle child : children) {
				if (child.isAlive()) {
					child.destroyForcibly();
				}
			}
			// Give the still-running parent a chance to reap forcibly killed children too.
			politeDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));
			while (children.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < politeDeadline) {
				interrupted |= pause();
			}
			if (process.isAlive()) {
				process.destroy();
			}
			politeDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200));
			while (process.isAlive() && System.nanoTime() < politeDeadline) {
				interrupted |= pause();
			}
			if (process.isAlive()) {
				process.destroyForcibly();
			}
			// Some JDK pipe closes wait for an in-progress read/write. Bound those too.
			for (var stream : List.of(process.getInputStream(), process.getErrorStream(), process.getOutputStream())) {
				workers.add(daemon("claude-cli-close", () -> {
					try {
						stream.close();
					}
					catch (IOException ex) {
						if (primary != null) {
							primary.addSuppressed(ex);
						}
					}
				}));
			}
			while ((process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)
					|| workers.stream().anyMatch(Thread::isAlive)) && System.nanoTime() < deadline) {
				interrupted |= pause();
			}
			if (process.isAlive() || children.stream().anyMatch(ProcessHandle::isAlive)
					|| workers.stream().anyMatch(Thread::isAlive)) {
				IOException failure = new IOException("CLI cleanup exceeded the five-second grace");
				if (primary == null) {
					throw new ClaudeCodeException(failure.getMessage(), failure);
				}
				primary.addSuppressed(failure);
			}
		}
		catch (RuntimeException ex) {
			if (primary != null) {
				primary.addSuppressed(ex);
			}
			else {
				throw ex;
			}
		}
		finally {
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}
	}

	private static boolean pause() {
		try {
			Thread.sleep(10);
			return false;
		}
		catch (InterruptedException ex) {
			return true;
		}
	}

	private static Thread daemon(String name, Runnable body) {
		Thread thread = new Thread(body, name);
		thread.setDaemon(true);
		thread.start();
		return thread;
	}

	/** A bounded byte ring; a noisy stderr cannot block the process or grow memory. */
	private static final class Tail {
		private final byte[] bytes;
		private int next;
		private int size;
		private boolean truncated;

		Tail(int capacity) {
			this.bytes = new byte[capacity];
		}

		synchronized void append(byte[] buffer, int count) {
			for (int i = 0; i < count; i++) {
				this.truncated |= this.size == this.bytes.length;
				this.bytes[this.next] = buffer[i];
				this.next = (this.next + 1) % this.bytes.length;
				this.size = Math.min(this.bytes.length, this.size + 1);
			}
		}

		synchronized String text() {
			byte[] ordered = new byte[this.size];
			int start = (this.next - this.size + this.bytes.length) % this.bytes.length;
			for (int i = 0; i < this.size; i++) {
				ordered[i] = this.bytes[(start + i) % this.bytes.length];
			}
			return (this.truncated ? "[stderr truncated; tail follows]\n" : "")
					+ new String(ordered, StandardCharsets.UTF_8);
		}
	}

}
