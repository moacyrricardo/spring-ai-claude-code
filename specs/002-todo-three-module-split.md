# 002 — Split into three Maven modules

## Context

The library is one artifact compiled against Spring AI 2.0.0 and Spring Boot 4.1.0. Those
are current, but most applications are not on them yet, and an application cannot adopt a
test dependency that drags a newer Spring AI onto its classpath. The result is that the
teams who would benefit most — those with an existing suite burning API tokens — cannot use
it at all.

Two thirds of the code has nothing to do with Spring. The CLI transport, the response
envelope, and the whole record/replay layer carry no Spring AI types at all. Only six
files touch Spring AI. Publishing that Spring-free core separately also makes it usable from a
plain JUnit test with no Spring context at all.

**Correction to the request as stated: there is no Spring AI 1.4.** The 1.x line is
`1.0.0`–`1.0.9` and `1.1.0`–`1.1.8`; the latest 1.x release is **1.1.8**. This spec targets
1.1.8 with Spring Boot 3.5.16 (latest 3.5.x). Everything below is written against the
versions that exist.

## Decision

Three modules under a parent POM:

| Module | Coordinates | Depends on |
|---|---|---|
| Core | `com.iskeru:claudecode-cli` | Jackson only |
| Current adapter | `com.iskeru:spring-ai-claudecode-p` | core + Spring AI 2.0.x / Boot 4.1.x |
| Legacy adapter | `com.iskeru:spring-ai-claudecode-p-1x` | core + Spring AI 1.1.8 / Boot 3.5.x |

The current adapter keeps its existing coordinates, so the artifact already pushed to
GitHub does not change identity — it only gets thinner as the core moves out from under it.

The two adapters share one source directory, with only genuinely version-specific classes
duplicated per module. Full duplication was rejected: the adapter is six files, four of
which are identical across versions, and duplicating them means every fix lands twice and
they drift apart silently.

## Implementation

**Module contents.**

- **Core** — `cli/` (`ClaudeCodeCli`, `ClaudeCodeCliRequest`, `ClaudeCodeCliResponse`,
  `ProcessClaudeCodeCli`, `ArgumentSpill`), `replay/` (all of it), `ClaudeCodeException`.
  Verified: nothing in these packages imports `org.springframework`.
- **Adapters** — `ConversationRenderer` and `DefaultConversationRenderer` (identical across
  versions), `autoconfigure/ClaudeCodeChatAutoConfiguration` and `ClaudeCodeChatProperties`
  (identical), `ClaudeCodeChatModel` (differs by one override), `ClaudeCodeChatOptions`
  (genuinely different — see the delta table).

**One dependency the core cannot inherit.** `ReplayingClaudeCodeCli` logs through
`org.apache.commons.logging`, which today arrives transitively from `spring-core` via
`spring-jcl`. A core module with no Spring dependency must declare it explicitly. Options:
depend on `org.springframework:spring-jcl` (Spring-authored but free of Spring APIs, and
what both adapters will already have), depend on `commons-logging:commons-logging`, or drop
to `java.util.logging` and take no dependency at all. **Recommendation: `spring-jcl`** — it
keeps one logging facade across all three modules and adds nothing to an adapter's
classpath that is not already there.

**Verified API deltas** between 1.1.8 and 2.0.0, from `javap` against both jars:

| Member | 1.1.8 | 2.0.0 |
|---|---|---|
| `ChatOptions.copy()` | `<T extends ChatOptions> T copy()`, **abstract** | absent |
| `ChatOptions.mutate()` | absent | `ChatOptions.Builder<?> mutate()` |
| `ChatOptions.Builder` | **non-generic** interface | `Builder<B extends Builder<B>>`, adds `clone()` |
| `ChatModel.getOptions()` | absent | `default ChatOptions getOptions()` |
| `ChatModel.stream(Prompt)` | default, throws `UnsupportedOperationException` | identical |
| `Media`, `MediaContent` | `org.springframework.ai.content` | identical |

So the divergence is concentrated almost entirely in **`ClaudeCodeChatOptions`**: its
builder must implement a generic self-type in 2.0 and a plain interface in 1.1, and `copy()`
is a required override in 1.1 but a plain method in 2.0. `ClaudeCodeChatModel` differs by a
single `getOptions()` override. The renderer and its interface are identical.

That `stream()` throws in *both* versions is worth recording: the single-element `Flux` is
required in the legacy module too, for the same reason.

**Sharing mechanism.** A top-level `spring-ai-adapter/src/main/java` directory is added to
both adapter modules' compile roots via `build-helper-maven-plugin:add-source`.
`ClaudeCodeChatOptions` is *not* in the shared directory — each module carries its own,
under the same fully-qualified name, so the shared classes compile against whichever is
present. The same arrangement applies to the shared test sources, so both modules run the
identical suite.

**Fixtures.** The replay fixtures used by the shared tests live once, in the core module's
test resources, and are read by both adapters. Identical fixtures across both proves the
adapters are behaviourally equivalent, which is the real contract.

**Auto-configuration.** Both Boot 3.5 and 4.1 use the same
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
mechanism, so each adapter ships its own copy of that file with no structural difference.

**Build.** The parent POM declares the reactor and shared plugin configuration but pins no
Spring version; each adapter imports its own BOM. Java 17 stays the target for all three —
it is the baseline for both Boot 3.5 and Boot 4.1.

**Validation.** The split is behaviour-preserving. The current 104-test suite must pass
unchanged against the 2.0 adapter, and the shared subset must pass against the 1.1 adapter,
before the split is considered done.

## Known Gaps

- **Unverified beyond the table above.** Only the types the code touches today were
  compared. `ChatResponseMetadata` / `ChatGenerationMetadata` builder surfaces and
  `DefaultUsage` constructors are *assumed* compatible and will be confirmed by compiling
  the legacy module — the first build is the test.
- **Spring AI 1.0.x is not targeted.** Only 1.1.x. Supporting 1.0 would likely mean a
  fourth module; no demand established.
- **No cross-version integration test.** Nothing proves an application on Boot 3.5 wires
  the legacy adapter end to end; the module's own auto-configuration test is the closest
  proxy.
- **The core's package name will read oddly.** It stays
  `com.iskeru.springai.claudecode.*` in a module that no longer depends on Spring. Renaming
  it would be a second breaking change for no functional gain; deferred unless the core is
  ever published for standalone use.
- **Release process gets heavier.** Three artifacts version and publish together, and a
  core change now requires rebuilding both adapters.
- **Interacts with 001.** If media support lands first, it is implemented once in core and
  the adapters only translate `org.springframework.ai.content.Media` — which sits in the
  same package in both versions, so the split does not complicate it.
