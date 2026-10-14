package com.iskeru.springai.claudecode.replay;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iskeru.springai.claudecode.ClaudeCodeException;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCli;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliRequest;
import com.iskeru.springai.claudecode.cli.ClaudeCodeCliResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(15)
class ConcurrentRecordingTests {

	private static final ClaudeCodeCliRequest REQUEST = ClaudeCodeCliRequest.builder().prompt("same").build();

	private static final ClaudeCodeCliResponse RESPONSE = ClaudeCodeCliResponse.fromJson("{\"result\":\"ok\"}", new ObjectMapper());

	@Test
	void sameKeyWaitsForOnePublishedRecording() throws Exception {
		var enteredSave = new CountDownLatch(1);
		var releaseSave = new CountDownLatch(1);
		var calls = new AtomicInteger();
		var store = new MemoryStore() {
			@Override
			public void save(String key, ClaudeCodeCliRequest request, ClaudeCodeCliResponse response) {
				enteredSave.countDown();
				await(releaseSave);
				super.save(key, request, response);
			}
		};
		var cli = auto(r -> { calls.incrementAndGet(); return RESPONSE; }, store);
		var leader = start(() -> cli.execute(REQUEST));
		try {
			await(enteredSave);
			var follower = start(() -> cli.execute(REQUEST));
			waiting(follower.thread());
			assertThat(follower.task().isDone()).isFalse();
			releaseSave.countDown();
			assertThat(leader.task().get(5, TimeUnit.SECONDS).replayed()).isFalse();
			assertThat(follower.task().get(5, TimeUnit.SECONDS)).isEqualTo(RESPONSE.asReplayed());
			assertThat(calls).hasValue(1);
		}
		finally { releaseSave.countDown(); }
	}

	@Test
	void differentKeysExecuteConcurrently() throws Exception {
		var barrier = new CyclicBarrier(2);
		var cli = auto(r -> {
			try { barrier.await(5, TimeUnit.SECONDS); }
			catch (Exception ex) { throw new RuntimeException(ex); }
			return RESPONSE;
		}, new MemoryStore());
		var first = start(() -> cli.execute(REQUEST));
		var second = start(() -> cli.execute(ClaudeCodeCliRequest.builder().prompt("different").build()));
		assertThat(first.task().get(5, TimeUnit.SECONDS).replayed()).isFalse();
		assertThat(second.task().get(5, TimeUnit.SECONDS).replayed()).isFalse();
	}

	@Test
	void delegateFailureIsSharedAndLaterCallsCanRetry() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var calls = new AtomicInteger();
		var failure = new ClaudeCodeException("recording failed");
		var cli = auto(r -> {
			if (calls.incrementAndGet() == 1) {
				entered.countDown();
				await(release);
				throw failure;
			}
			return RESPONSE;
		}, new MemoryStore());
		var leader = start(() -> cli.execute(REQUEST));
		try {
			await(entered);
			var follower = start(() -> cli.execute(REQUEST));
			waiting(follower.thread());
			release.countDown();
			assertThatThrownBy(() -> leader.task().get(5, TimeUnit.SECONDS)).hasCause(failure);
			assertThatThrownBy(() -> follower.task().get(5, TimeUnit.SECONDS)).hasCause(failure);
			assertThat(cli.execute(REQUEST)).isEqualTo(RESPONSE);
			assertThat(calls).hasValue(2);
		}
		finally { release.countDown(); }
	}

	@Test
	void saveFailureIsSharedAndDoesNotLeaveAStuckEntry() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var saves = new AtomicInteger();
		var failure = new ClaudeCodeException("disk full");
		var store = new MemoryStore() {
			@Override
			public void save(String key, ClaudeCodeCliRequest request, ClaudeCodeCliResponse response) {
				if (saves.incrementAndGet() == 1) {
					entered.countDown();
					await(release);
					throw failure;
				}
				super.save(key, request, response);
			}
		};
		var cli = auto(r -> RESPONSE, store);
		var leader = start(() -> cli.execute(REQUEST));
		try {
			await(entered);
			var follower = start(() -> cli.execute(REQUEST));
			waiting(follower.thread());
			release.countDown();
			assertThatThrownBy(() -> leader.task().get(5, TimeUnit.SECONDS)).hasCause(failure);
			assertThatThrownBy(() -> follower.task().get(5, TimeUnit.SECONDS)).hasCause(failure);
			assertThat(cli.execute(REQUEST).replayed()).isFalse();
			assertThat(cli.execute(REQUEST).replayed()).isTrue();
		}
		finally { release.countDown(); }
	}

	@Test
	void interruptedFollowerDoesNotCancelLeaderAndRestoresInterrupt() throws Exception {
		var entered = new CountDownLatch(1);
		var release = new CountDownLatch(1);
		var cli = auto(r -> { entered.countDown(); await(release); return RESPONSE; }, new MemoryStore());
		var leader = start(() -> cli.execute(REQUEST));
		var interrupted = new AtomicReference<Boolean>();
		try {
			await(entered);
			var follower = start(() -> {
				try { return cli.execute(REQUEST); }
				finally { interrupted.set(Thread.currentThread().isInterrupted()); }
			});
			waiting(follower.thread());
			follower.thread().interrupt();
			assertThatThrownBy(() -> follower.task().get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ClaudeCodeException.class);
			assertThat(interrupted.get()).isTrue();
			release.countDown();
			assertThat(leader.task().get(5, TimeUnit.SECONDS)).isEqualTo(RESPONSE);
			assertThat(cli.execute(REQUEST)).isEqualTo(RESPONSE.asReplayed());
		}
		finally { release.countDown(); }
	}

	@Test
	void leaderRechecksAfterAnInitialMiss() {
		var loads = new AtomicInteger();
		var store = new MemoryStore() {
			@Override
			public Optional<ClaudeCodeCliResponse> load(String key) {
				return loads.incrementAndGet() == 1 ? Optional.empty() : Optional.of(RESPONSE);
			}
		};
		assertThat(auto(r -> { throw new AssertionError("must recheck"); }, store).execute(REQUEST))
			.isEqualTo(RESPONSE.asReplayed());
	}

	private static ReplayingClaudeCodeCli auto(ClaudeCodeCli delegate, FixtureStore store) {
		return ReplayingClaudeCodeCli.builder().delegate(delegate).store(store).mode(ReplayMode.AUTO).build();
	}

	private static void await(CountDownLatch latch) {
		try { assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue(); }
		catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new RuntimeException(ex); }
	}

	private static void waiting(Thread thread) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.getState() != Thread.State.WAITING && thread.isAlive() && System.nanoTime() < deadline) {
			Thread.yield();
		}
		assertThat(thread.getState()).isEqualTo(Thread.State.WAITING);
	}

	private record Running(Thread thread, FutureTask<ClaudeCodeCliResponse> task) { }

	private static Running start(java.util.concurrent.Callable<ClaudeCodeCliResponse> action) {
		var task = new FutureTask<>(action);
		var thread = new Thread(task);
		thread.setDaemon(true);
		thread.start();
		return new Running(thread, task);
	}

	private static class MemoryStore implements FixtureStore {
		private final ConcurrentHashMap<String, ClaudeCodeCliResponse> values = new ConcurrentHashMap<>();
		public Optional<ClaudeCodeCliResponse> load(String key) { return Optional.ofNullable(this.values.get(key)); }
		public void save(String key, ClaudeCodeCliRequest request, ClaudeCodeCliResponse response) { this.values.put(key, response); }
		public String describe() { return "memory"; }
	}

}
