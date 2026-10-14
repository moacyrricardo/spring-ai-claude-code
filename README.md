# spring-ai-claudecode-p

A Spring AI `ChatModel` backed by the locally installed `claude` CLI running in
non-interactive mode (`claude -p`).

The point is cost. A test suite that exercises real model behaviour normally needs an API
key, and every developer running it burns tokens against it. This model instead shells out
to the Claude Code binary the developer already has installed and authenticated, so the
calls bill against their existing subscription. Record the responses once and CI replays
them offline, deterministically, for free.

**This is test infrastructure, not a production model.** Each call spawns a process,
sampling parameters cannot be forwarded, and throughput is bounded by how many CLI
processes the machine will tolerate.

## Requirements

- Java 17+
- Spring AI 2.0.x **or** 1.1.x — one adapter per generation, see Coordinates
- Claude Code **2.1.296+**, installed and authenticated (`claude --version`) — except in
  `replay` mode, which needs neither. Live execution probes the version once per executor;
  an older or unrecognized version fails rather than dropping isolation flags.

## Coordinates

Three artifacts, one version, released together. Pick the adapter that matches the Spring AI
your application is already on; it brings the core with it.

Spring AI 2.0.x / Spring Boot 4.1.x:

```xml
<dependency>
    <groupId>com.iskeru</groupId>
    <artifactId>spring-ai-claudecode-p</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Spring AI 1.1.x / Spring Boot 3.5.x — same package names, same behaviour:

```xml
<dependency>
    <groupId>com.iskeru</groupId>
    <artifactId>spring-ai-claudecode-p-1x</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

The CLI transport, the response envelope and the whole record/replay layer carry no Spring
types at all, so they ship separately and can be used from a plain JUnit test with no Spring
on the classpath — the module depends on Jackson and nothing else, and the build fails if
that ever stops being true:

```xml
<dependency>
    <groupId>com.iskeru</groupId>
    <artifactId>claudecode-cli</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Everything lives under `com.iskeru.springai.claudecode`, whichever artifact it came from.

The one behaviour difference between the adapters: on 2.0 the CLI's cache token counts are
exposed as `Usage.getCacheReadInputTokens()` / `getCacheWriteInputTokens()`; 1.1's `Usage` has
no such fields, so there they are reachable through `Usage.getNativeUsage()` instead. Both
fold cache tokens into the prompt count identically.

## Quick start

Standalone, no Spring container:

```java
ChatModel model = ClaudeCodeChatModel.builder()
    .cli(ProcessClaudeCodeCli.builder().build())
    .defaultOptions(ClaudeCodeChatOptions.builder().model("sonnet").build())
    .build();

String answer = model.call("What is 2 + 2?");
```

Or let Boot wire it. Put the jar on the test classpath and the auto-configuration
contributes a `ClaudeCodeChatModel` when no other `ChatModel` bean is declared:

```properties
spring.ai.claude-code.options.model=sonnet
```

Every bean is `@ConditionalOnMissingBean`, so declaring your own `ClaudeCodeCli`,
`FixtureStore`, or `ConversationRenderer` replaces the corresponding default.

### Replacing an application's provider in tests

Adding this dependency does not automatically replace a provider starter or an explicitly
declared model. Disable the application's normal provider in the test profile and let this
adapter supply the single `ChatModel`. For applications using the OpenAI starter, this
recipe is tested against both Spring AI 2.0 / Boot 4.1 and Spring AI 1.1 / Boot 3.5:

```properties
# src/test/resources/application-claude-test.properties
spring.ai.model.chat=none
spring.ai.claude-code.enabled=true
spring.ai.claude-code.options.model=sonnet
spring.ai.claude-code.replay.mode=replay
spring.ai.claude-code.replay.directory=src/test/resources/claude-fixtures
```

Activate it with `@ActiveProfiles("claude-test")`. The `spring.ai.model.chat=none` switch
disables the standard provider chat auto-configuration; this adapter uses its own enable
property. Provider settings for embeddings, images, and audio are separate. If the test
also disables those models, set the corresponding `spring.ai.model.embedding`, `image`,
`moderation`, `audio.speech`, and `audio.transcription` properties to `none`.

Use the auto-configured `ChatClient.Builder`, or explicitly select the model:

```java
ChatClient client = ChatClient.builder(context.getBean(ClaudeCodeChatModel.class)).build();
```

If the application declares its model with `@Bean`, exclude that bean configuration in
this test profile. The adapter backs off for any existing `ChatModel`. Applications using
another provider must verify its disable switch; do not rely on auto-configuration order.

## Record and replay

Fixtures make the suite deterministic and free after the first pass. Set a mode:

```properties
spring.ai.claude-code.replay.mode=auto
spring.ai.claude-code.replay.directory=src/test/resources/claude-fixtures
```

| Mode     | Behaviour                                                                  |
| -------- | -------------------------------------------------------------------------- |
| `live`   | **Default.** Always call the CLI. Never reads or writes fixtures.           |
| `record` | Always call the CLI, and overwrite the fixture. Use this to re-capture.     |
| `replay` | Never call the CLI. Serve from fixtures; a miss fails the test.             |
| `auto`   | Replay when a fixture exists, otherwise call the CLI and record the result. |

The intended workflow is `auto` locally and `replay` in CI. New or changed prompts get
captured on a developer's machine and committed; CI then runs with no Claude Code
installation, no network, and no cost — and a prompt that changed without a re-record shows
up as a fixture miss rather than a silent live call.

A fixture is one JSON file per request, named `<prompt-slug>-<key>.json`, holding both the
request and the CLI's untouched response envelope:

```json
{
  "key" : "edc72af56d88141d7ea34ba60fcf90ca",
  "request" : {
    "model" : "sonnet",
    "systemPrompt" : "You are a helpful assistant.",
    "prompt" : "What is 2 + 2?",
    "tools" : [ ],
    "settingSources" : [ ]
  },
  "response" : {
    "result" : "4",
    "stop_reason" : "end_turn",
    "usage" : { "input_tokens" : 185, "output_tokens" : 5 }
  }
}
```

They are meant to be committed: a changed prompt appears in review as a readable diff.

The key is a truncated SHA-256 over the modeled semantic inputs — model, system prompt,
user prompt, tools, effort, JSON schema, setting sources, extra args, and the versioned
isolation policy. `maxBudgetUsd` is
deliberately excluded, since a spend guard is not a semantic input.

A replayed response carries `replayed=true` in its generation metadata. Its `usage` and
`totalCostUsd` describe the original recording, not the replay, which cost nothing.

**Isolation migration (spec 003):** request identity now includes `v1:ISOLATED` or
`v1:CUSTOM`, and direct-core isolated requests normalize tools/settings to empty lists and
use a plain default system prompt. Previously recorded keys intentionally miss. Re-record
with `record` or `auto`, review and commit the replacement fixtures, then return CI to
`replay`. Old JSON envelopes remain readable, but are not silently reused under the new
policy. Changing only the subsequent media implementation will not change text-only keys.

**Concurrent AUTO calls:** within one `ReplayingClaudeCodeCli` instance, concurrent misses
for the same key share one delegate execution and wait for its fixture to be saved. The
leader is live; followers carry `replayed=true`. Distinct keys can execute concurrently.
An interrupted follower does not cancel the leader. Recording/save failures reach all
current waiters, and later calls can retry. RECORD still calls the delegate every time.
Share one decorator per recording directory; coordination does not span wrappers or JVMs.

Fixtures are published by atomic replacement where supported, with a regular replacement
fallback on other filesystems. Failed writes clean up their temporary files. Corrupt
fixtures are errors, including in AUTO; they never trigger a silent live re-record.

## When replay pays

Replay is not only about re-running a suite tomorrow. A fixture is keyed by the *request*,
not by the caller, so anything issuing the same prompt shares one recording.

**One prompt, many assertions.** This is the common shape and the one worth designing for.
A single interesting response usually deserves several independent checks — the text, the
token accounting, the finish reason, how your parser maps it — and each belongs in its own
test with its own name and its own failure message. Written naively that is one model call
per test. Here it is one call total: the first records, the rest replay, *within the same
suite run*. Splitting one fat assertion into five focused ones costs nothing.

```java
// Three tests, one fixture, one model call.
assertThat(model.call(prompt).getResult().getOutput().getText()).contains("refund");
assertThat(model.call(prompt).getMetadata().getUsage().getTotalTokens()).isPositive();
assertThat(model.call(prompt).getResult().getMetadata().getFinishReason()).isEqualTo("end_turn");
```

`OneFixtureManyAssertionsTests` pins this behaviour.

**The same suite over time.** One recording amortises across every push, every developer,
every watch-mode rerun — thousands of executions from a single call.

**Testing the code around the model.** Response parsing, retry logic, template rendering,
downstream branching. The model call is a fixed input; you are not testing Claude.

**The inner loop.** Re-running one test forty times while fixing a parser is instant and
free, and the response stops shifting under you while you debug.

**Onboarding and offline work.** In `replay` mode a new contributor runs the suite with no
Claude Code install, no authentication, and no subscription.

### What has to stay stable

The key hashes the prompt exactly. A prompt carrying `Instant.now()`, a random UUID, or a
generated identifier misses on **every** run — you record fixtures that can never be hit
again, and `replay` mode then fails permanently.

Inject a fixed `Clock`, seed generators, use stable identifiers. This is ordinary
deterministic-test discipline; a prompt that cannot be made stable was not a reliable test
to begin with.

### When not to replay

- **Evaluating model quality.** If the question is "is Claude still good at this?", a
  recorded answer answers nothing. Use `@Tag("live")` and `mvn test -Plive`.
- **Verifying a model upgrade.** Stale fixtures hide precisely the change you are looking
  for. Re-record with `record` mode.
- **Testing behaviour under varied phrasing** — where non-determinism is the subject.

The rule of thumb: `replay` for tests that happen to call an LLM, live for tests about the
LLM. Nearly all of them are the former.

## Agent behaviour is off by default

Claude Code is a coding agent; a `ChatModel` is not. Unless told otherwise this model runs
the CLI with:

- `--tools ""` — disable built-in tools
- `--setting-sources ""` — disable user/project/local settings sources
- `--safe-mode --strict-mcp-config --disable-slash-commands` — disable customizations,
  account/project MCP connections, and skills; the live compatibility test verifies
  empty tool, MCP-server, and skill lists
- `--system-prompt "You are a helpful assistant."` — replacing the agent system prompt

This keeps calls smaller and more predictable. Built-in plugins can still be listed, and
administrator policy and inherited environment remain outside this guarantee. Token counts
vary with CLI/model versions. `--bare` is not used because it disables subscription OAuth.

A `SystemMessage` in the `Prompt` overrides the configured system prompt. To opt back into
tools/settings, explicitly select CUSTOM and name the tools:

```properties
spring.ai.claude-code.options.isolation=custom
spring.ai.claude-code.options.tools=Read,Grep
```

Standalone callers use `.isolation(IsolationPolicy.CUSTOM)` on the options or request
builder. CUSTOM permits external context, so record/replay cannot identify changes to
tool results or other ambient inputs. Model aliases can also move; pin a full model ID
when comparing results across model versions.

ISOLATED extra arguments allow only `--max-turns N` or `--max-turns=N`, with a positive
integer. Unknown flags require CUSTOM. Both policies reserve transport/output and session
continuation flags (including `--verbose`, `--input-format`, `--output-format`, `--resume`,
and `--continue`); pass modeled values through their setters rather than overriding flags.

## Configuration

| Property                                     | Default                             |
| -------------------------------------------- | ----------------------------------- |
| `spring.ai.claude-code.enabled`               | `true`                              |
| `spring.ai.claude-code.executable`            | `claude`                            |
| `spring.ai.claude-code.timeout`               | `5m`                                |
| `spring.ai.claude-code.max-stdout-bytes`      | `16777216` (16 MiB)                  |
| `spring.ai.claude-code.max-stderr-bytes`      | `65536` (64 KiB tail)                |
| `spring.ai.claude-code.working-directory`     | JVM working directory               |
| `spring.ai.claude-code.session-persistence`   | `false`                             |
| `spring.ai.claude-code.environment.*`         | —                                   |
| `spring.ai.claude-code.replay.mode`           | `live`                              |
| `spring.ai.claude-code.replay.directory`      | `src/test/resources/claude-fixtures`|
| `spring.ai.claude-code.options.model`         | CLI default                         |
| `spring.ai.claude-code.options.isolation`     | `isolated`                          |
| `spring.ai.claude-code.options.system-prompt` | `You are a helpful assistant.`      |
| `spring.ai.claude-code.options.tools`         | none                                |
| `spring.ai.claude-code.options.effort`        | CLI default                         |
| `spring.ai.claude-code.options.fallback-models` | —                                 |
| `spring.ai.claude-code.options.max-budget-usd`  | —                                 |
| `spring.ai.claude-code.options.json-schema`     | —                                 |
| `spring.ai.claude-code.options.extra-args`      | —                                 |

## Limitations

Worth knowing before you write assertions against this.

- **Timeout cleanup has a bounded grace.** Each model call has its configured timeout;
  the first call also performs a version probe bounded by the lesser of 10 seconds and
  that timeout. Abnormal termination stops known descendants and the parent, closes pipes,
  and allows at most five additional seconds for cleanup. Already detached/reparented
  processes cannot be guaranteed across operating systems.
- **Output capture is bounded.** Stdout overflow fails and cleans up the process; stderr
  retains a marked tail. Stream-read failures surface as errors instead of empty answers.
- **PDF/image attachments are not implemented yet.** They fail explicitly; spec 001 is
  the next implementation step. Existing OCR text can already use the text path.

- **Sampling parameters do not work.** `temperature`, `topP`, `topK`, `maxTokens`,
  `stopSequences`, `frequencyPenalty` and `presencePenalty` can be set — they are part of
  the `ChatOptions` contract — but the CLI has no flag to forward them, so they are
  ignored. The model logs a warning the first time it sees one set. A test that depends on
  a specific temperature needs an API-backed model.
- **`stream()` is not streaming.** It emits the completed answer as a single element. It
  exists so code under test that calls `ChatClient.stream()` keeps working; assertions
  about chunk boundaries or time-to-first-token will not be meaningful.
- **No tool calling.** Spring AI `ToolCallback`s are not bridged. The CLI runs its own
  agentic loop, which is a different model from Spring AI's tool-call protocol.
- **No embeddings.** The CLI has no embedding endpoint.
- **Multi-turn is flattened.** Each `-p` run is independent, so prior turns are serialised
  into the prompt as a tagged transcript rather than sent as a message array. Swap
  `ConversationRenderer` if your assertions need a different shape.
- **Process overhead.** Expect a few hundred milliseconds of startup per call on top of
  inference — one more reason to replay in CI.
- **Prompt size avoids command-line limits.** The prompt travels on the CLI's stdin —
  verified at 100 KB against the real binary and at 512 KiB through the process plumbing.
  The CLI's own input limit (currently documented as 10 MB for piped text), the model's
  context window, and the configured timeout still apply. A large prompt is expensive live
  (100 KB cost $0.15 in one call), which is exactly where `replay` earns its keep.
- **Options that become command-line flags are bounded.** Linux caps a single `argv` entry
  at 128 KiB. A `systemPrompt` or `appendSystemPrompt` above 64 KiB is therefore written
  to a temporary file and passed as `--system-prompt-file`; this is invisible to the CLI
  and cannot change a fixture key. `jsonSchema` has no file form, so an oversized schema
  fails with an explicit error rather than an opaque `exec` failure.

## Building

```
claudecode-p-parent/              reactor and shared plugin configuration
├── claudecode-cli/               the core: CLI, envelope, record/replay — Jackson only
├── spring-ai-adapter/            adapter sources shared by both modules below; not a module
├── spring-ai-claudecode-p/       Spring AI 2.0 / Boot 4.1
└── spring-ai-claudecode-p-1x/    Spring AI 1.1 / Boot 3.5
```

`spring-ai-adapter` has no POM: both adapter modules add its `src/main/java` and
`src/test/java` to their own compile roots via `build-helper-maven-plugin`, so the adapter is
one set of sources compiled twice rather than a copy that drifts. Only
`ClaudeCodeChatOptions` and `ClaudeCodeUsage` exist once per module, under the same
fully-qualified names, because their Spring AI supertypes genuinely differ between the
generations. The shared tests run in both modules and are what keeps the two twins in step.

```bash
mvn test          # whole reactor; no CLI needed, nothing is spent
mvn test -Plive   # end-to-end against the real binary; consumes subscription usage
mvn install

# one module at a time — -am is required, the core is not installed yet
mvn -pl claudecode-cli test
mvn -pl spring-ai-claudecode-p-1x -am test
```

`-Plive` tests live in `spring-ai-claudecode-p` only: shared test sources compile into both
adapters, so putting them there would spend subscription usage twice per run.

GitHub Actions runs the offline reactor on Java 17 and Java 25. Live tests remain an
explicit local operation; they include inspection of the CLI's initialization event to
check isolation on the installed version.

## Design

```
Prompt ──► ClaudeCodeChatModel ──► ConversationRenderer ──► ClaudeCodeCliRequest
                                                                    │
                                              ReplayingClaudeCodeCli ├─► FixtureStore
                                                                    │
                                                ProcessClaudeCodeCli └─► `claude -p`
                                                                              │
ChatResponse ◄─────────────────────────────────── ClaudeCodeCliResponse ◄─────┘
```

`ClaudeCodeCli` is the seam. `ProcessClaudeCodeCli` runs the binary;
`ReplayingClaudeCodeCli` decorates it with the fixture store. Substituting either — or a
plain fake — needs no changes to the model.
