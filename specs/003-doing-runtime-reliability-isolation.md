Status: doing
Branch: moacyrricardo/spec-003-runtime-reliability-isolation

# 003 — Runtime reliability and isolation

## Context

The October 2026 audit passed `mvn clean verify` (153 offline tests: 65 core and
44 per adapter) and `mvn test -Plive` (5 live tests) on Java 25 with Java 17
compilation targets and Claude Code 2.1.296. Additional probes reproduced gaps
outside that suite:

- `--tools "" --setting-sources ""` still loaded account-connected MCP tools and
  skills. Those inputs are absent from fixture identity.
- Two concurrent identical AUTO misses invoked the delegate twice and wrote the
  same fixture twice.
- Killing a timed-out parent left its child process running.
- An existing provider and this auto-configuration produced two `ChatModel` beans.

The text transport is working, but these gaps weaken its central promises:
controlled calls, deterministic replay, and predictable test integration.

## Decision

Harden the existing three-module design before implementing media support (001).
Keep the core Spring-free. Make isolated invocation the default, explicitly
coordinate concurrent AUTO misses, bound process cleanup and output capture, and
provide an explicit Spring test-provider replacement recipe.

**Dependencies:** 002 is complete. Implementation order is **003 → 001 → 004**.
This spec owns process lifecycle, isolation, and concurrent recording. Spec 001
owns attachment transport and structured response mapping.

## Implementation

### Isolation policy and CLI compatibility

Represent the invocation policy in `ClaudeCodeCliRequest` as a core-owned enum,
`ISOLATED` or `CUSTOM`, exposed through both options twins and Boot properties as
`spring.ai.claude-code.options.isolation`. Default to `ISOLATED`.

For ISOLATED, build the command with:

```text
--tools "" --setting-sources ""
--safe-mode --strict-mcp-config --disable-slash-commands
```

Retain the plain assistant system prompt and default disabled session persistence.
The audit verified empty tools, MCP-server, and skill arrays with this combination
on 2.1.296. Built-in plugins were still listed; do not promise a plugin-free
process or complete independence from administrator policy and environment.

Use 2.1.296 as the initial minimum supported live CLI version. Probe and cache the
version once per process-executor instance before its first execution. An older or
unrecognizable version fails with the detected version and upgrade requirement;
never fall back by dropping isolation flags. REPLAY does not probe or launch a
binary. Document that newer versions still need the live compatibility test.

ISOLATED rejects non-empty tool/settings lists and extra arguments that enable
external context, resume sessions, or override managed transport/isolation flags.
Use an explicit allowlist for isolated extra arguments; unknown flags require
CUSTOM. CUSTOM preserves deliberate tool/settings opt-ins, but cannot override
the transport framing. Publish the allowed flags and test argument/value parsing,
including `--flag=value`. Core validation runs before fixture lookup.

Do not substitute `--bare`: current CLI documentation says it skips subscription
OAuth authentication. Preserve the authenticated CLI workflow this project serves.

**Fixture migration:** isolation changes semantic inputs. Append a versioned
isolation-policy marker to new request keys and record it in the fixture request
description. Old keys must not silently satisfy isolated requests. Existing JSON
fixtures remain readable by the store, but users deliberately re-record affected
requests. Document this one-time migration; spec 001 pins the text keys established
after this change. CUSTOM keys identify the policy but cannot capture arbitrary
external tool state, so its reproducibility guarantee is narrower.

### Concurrent AUTO requests

Implement per-key single-flight coordination inside each `ReplayingClaudeCodeCli`
instance. After a miss, elect one leader and recheck the store before invoking the
delegate. Concurrent followers await the same completion rather than recording
independently. Distinct keys remain concurrent.

- Publish completion only after the fixture save succeeds.
- The leader returns the live response; followers return the same response marked
  replayed, since they did not perform another live invocation.
- Share a recording/save failure with current followers. Remove the in-flight
  entry in all completion paths so a later call can retry.
- An interrupted follower restores its interrupt status and fails without
  cancelling the leader for other callers.
- REPLAY still fails immediately on a miss. RECORD intentionally invokes the
  delegate for each call. LIVE remains undecorated execution.

Keep fixture publication write-then-move; use atomic replacement where the
filesystem supports it, with a documented replacement fallback. Always clean up
temporary files after failures. Do not mistake corrupt fixtures for misses.

The guarantee is one shared decorator instance within one JVM. Multiple wrappers
or JVMs must not concurrently record to the same directory; cross-process locking
is deferred and this constraint belongs in the README.

### Process lifecycle and diagnostics

Refactor `ProcessClaudeCodeCli` around a single cleanup path for timeout,
interruption, pump failure, and normal exit. On abnormal termination, snapshot
descendants while the parent is alive, terminate the tree, escalate surviving
processes to forcible termination, and await exit within a bounded cleanup grace.
Use a maximum five-second cleanup grace after the execution deadline. Close all
pipes and finish pump threads within that shared grace rather than adding a full
grace separately for every thread. Preserve caller interruption.

Validate executable, mapper, positive timeout, and spill threshold before launch.
Record stdout/stderr pump exceptions instead of returning empty strings. Preserve
the primary timeout/interruption failure and attach cleanup failures as causes or
suppressed diagnostics. Register spill files for cleanup immediately after creation.

Replace unbounded `readAllBytes()` capture with bounded readers. Default stdout
capture to 16 MiB and retain the last 64 KiB of stderr, marking stderr truncation.
Expose positive limits on the process builder and under Boot process properties.
Exceeding stdout capacity fails the call and triggers cleanup; do not return a
truncated successful answer. Spec 001 adds event-aware bounds for NDJSON.

### Spring provider selection

Change the model bean condition to `@ConditionalOnMissingBean(ChatModel.class)`.
With a user-declared model, back off instead of silently adding a competing model.
This is not an automatic replacement mechanism for arbitrary provider starters:
auto-configuration order varies and needs an explicit integration recipe.

Add README examples for both adapter generations that disable the application's
normal provider auto-configuration in a test profile, enable this adapter, and
construct/select the `ChatClient` from the resulting model. Verify the recipe with
a representative provider starter per generation; add it only as a test dependency.
Do not require both adapter artifacts on the same classpath.

### Validation

1. `mvn clean verify` passes for the core and both adapters, including all existing
   behaviors except the explicitly documented isolation/key migration and bean
   backoff change.
2. Stub-executable tests cover version parsing, unsupported versions, conflicting
   flags, default isolation, and explicit CUSTOM behavior. REPLAY succeeds with a
   nonexistent executable and never probes its version.
3. A live-tagged initialization-event test on the supported CLI asserts empty
   tools, MCP-server, and skill lists in default isolated mode. It records the CLI
   version and verifies a simple completion still works with existing CLI auth.
4. Barrier-based tests prove two concurrent same-key AUTO requests execute once,
   distinct keys overlap, followers receive the same answer, failures release
   waiters, and a later call can retry. Avoid timing-only assertions.
5. Process tests start a parent and child, capture their PIDs, trigger timeout and
   interruption, and assert both exit and pumps finish. Clean up the test's own
   processes even if assertions fail. Cover output overflow and pump failures.
6. Both adapter suites verify user-model backoff and the explicit provider-disable
   recipe, with exactly one selected `ChatModel` and calls reaching a fake CLI.
7. Pin a post-migration text fixture key for 001. Test that legacy fixtures cannot
   accidentally satisfy isolated requests and that their stored envelopes remain
   readable.
8. Add an offline CI matrix on Java 17 and a current supported JDK for the whole
   reactor. Run live checks explicitly, once in the 2.0 adapter, outside default CI.

## Implementation Checklist

Implement and review in the following order. Check items only after their regression
tests pass; opening the draft PR does not count as implementation completion.

- [ ] Add core isolation policy and validation, wire both options twins and Boot
      properties, and preserve existing construction APIs where possible.
- [ ] Add cached, bounded CLI version probing and isolated command construction;
      cover unsupported versions, conflicting flags, and replay without a binary.
- [ ] Version fixture identity for the isolation policy, document re-recording,
      and pin the post-migration text key consumed by spec 001.
- [ ] Add per-key AUTO single-flight coordination and deterministic concurrency,
      failure, retry, and interrupted-follower tests.
- [ ] Harden fixture publication and temporary-file cleanup on write failures.
- [ ] Centralize process-tree cleanup, bound stdout/stderr capture, preserve pump
      failures, and add timeout/interruption/overflow regression tests.
- [ ] Add `ChatModel` bean backoff and verify the provider-disable recipe against
      a representative provider in both adapter generations.
- [ ] Add the Java 17/current-JDK offline CI matrix and update configuration and
      compatibility documentation.
- [ ] Run `mvn clean verify` and the explicit live isolation/record-replay checks;
      record results and deviations before marking the PR ready for review.

## Known Gaps

- Cross-JVM and cross-instance recording coordination is deferred.
- CUSTOM mode can depend on external state that fixture keys do not describe.
- Administrator policy, inherited credentials, and CLI/model upgrades are not
  eliminated by isolation. Model aliases can change; pin a full model ID for
  comparisons and re-record intentionally.
- Java process-tree termination cannot guarantee cleanup of already detached or
  reparented descendants on every platform. Verify supported platforms and document
  the boundary rather than claiming OS-level containment.
- Actual response streaming, media, OCR preprocessing, and extraction accuracy are
  handled separately; no production-throughput claim is introduced.
