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
- Spring AI 2.0.x
- Claude Code installed and authenticated (`claude --version`) — except in `replay` mode,
  which needs neither

## Coordinates

```xml
<dependency>
    <groupId>com.iskeru</groupId>
    <artifactId>spring-ai-claudecode-p</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

Everything lives under `com.iskeru.springai.claudecode`.

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
contributes a `ClaudeCodeChatModel` (and therefore a `ChatClient`) that the code under
test uses in place of the real provider:

```properties
spring.ai.claude-code.options.model=sonnet
```

Every bean is `@ConditionalOnMissingBean`, so declaring your own `ClaudeCodeCli`,
`FixtureStore`, or `ConversationRenderer` replaces the corresponding default.

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

The key is a SHA-256 over everything that can change the answer — model, system prompt,
user prompt, tools, effort, JSON schema, setting sources, extra args. `maxBudgetUsd` is
deliberately excluded, since a spend guard is not a semantic input.

A replayed response carries `replayed=true` in its generation metadata. Its `usage` and
`totalCostUsd` describe the original recording, not the replay, which cost nothing.

## Agent behaviour is off by default

Claude Code is a coding agent; a `ChatModel` is not. Unless told otherwise this model runs
the CLI with:

- `--tools ""` — no file editing, no shell, no web access
- `--setting-sources ""` — no `CLAUDE.md`, hooks, or personal configuration bleeding into
  answers
- `--system-prompt "You are a helpful assistant."` — replacing the agent system prompt

That last one is not just about behaviour: the default agent prompt costs roughly 11k input
tokens per call, versus about 185 for a plain completion.

A `SystemMessage` in the `Prompt` overrides the configured system prompt. To opt back into
tools, name them (`tools: Read,Grep`) or use the CLI's own catch-all (`tools: default`).

## Configuration

| Property                                     | Default                             |
| -------------------------------------------- | ----------------------------------- |
| `spring.ai.claude-code.enabled`               | `true`                              |
| `spring.ai.claude-code.executable`            | `claude`                            |
| `spring.ai.claude-code.timeout`               | `5m`                                |
| `spring.ai.claude-code.working-directory`     | JVM working directory               |
| `spring.ai.claude-code.session-persistence`   | `false`                             |
| `spring.ai.claude-code.environment.*`         | —                                   |
| `spring.ai.claude-code.replay.mode`           | `live`                              |
| `spring.ai.claude-code.replay.directory`      | `src/test/resources/claude-fixtures`|
| `spring.ai.claude-code.options.model`         | CLI default                         |
| `spring.ai.claude-code.options.system-prompt` | `You are a helpful assistant.`      |
| `spring.ai.claude-code.options.tools`         | none                                |
| `spring.ai.claude-code.options.effort`        | CLI default                         |
| `spring.ai.claude-code.options.fallback-models` | —                                 |
| `spring.ai.claude-code.options.max-budget-usd`  | —                                 |
| `spring.ai.claude-code.options.json-schema`     | —                                 |
| `spring.ai.claude-code.options.extra-args`      | —                                 |

## Limitations

Worth knowing before you write assertions against this.

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

## Building

```bash
mvn test          # unit tests; no CLI needed, nothing is spent
mvn test -Plive   # end-to-end against the real binary; consumes subscription usage
mvn install
```

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
