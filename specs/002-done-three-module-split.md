Status: done
Branch: moacyrricardo/spec-002-three-module-split

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

**Logging: the core takes no dependency at all.** `ReplayingClaudeCodeCli` currently logs
through `org.apache.commons.logging`, which reaches it only transitively from `spring-core`
via `spring-jcl`. Rather than declare a facade, the core switches to
**`java.util.logging`**, keeping the "Jackson only" row above literally true.

The footprint being replaced is one declaration and two call sites, both in
`ReplayingClaudeCodeCli` — a fixture-replayed `debug` and a fixture-recorded `info`. At that
size the dependency costs more than the abstraction buys.

Two alternatives were evaluated and rejected:

- **`slf4j-api`** — the ecosystem standard, 69 KB with no transitive dependencies, and
  already a compile-scope dependency of the current artifact (via
  `spring-ai-client-chat → json-schema-validator`). Rejected because with no binding on the
  classpath it silently discards output, which is exactly the plain-JUnit-without-Spring
  case the standalone core exists to serve. `java.util.logging` always produces output with
  no configuration, and Spring Boot bridges JUL into the application's logging by default,
  so Boot consumers lose nothing.
- **Lombok `@Slf4j`** — not a facade but a code generator that emits an SLF4J logger, so it
  requires `slf4j-api` anyway and inherits the objection above. It would also add an
  annotation processor to the build and oblige every contributor to install an IDE plugin,
  in exchange for removing a single line from a single class.

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

**Exactly one class is duplicated: `ClaudeCodeChatOptions`.** Each module carries its own
under the same fully-qualified name, so shared classes compile against whichever is present.
The other five adapter files — including `ClaudeCodeChatModel` — are shared.

`ClaudeCodeChatModel` can be shared despite the `getOptions()` difference, because on 2.0 it
overrides an interface default and on 1.1.8 it is simply an additional public method that
overrides nothing. The only thing preventing that today is the `@Override` annotation on
`ClaudeCodeChatModel#getOptions()`, which fails to compile on 1.1.8. **Removing that
annotation is part of this work**, and it is safe on 2.0: the method still overrides the
interface default, it is merely no longer asserted to.

**The contract between the two `ClaudeCodeChatOptions` twins.** Because shared code compiles
against whichever twin is present, both must expose the same surface everywhere shared
sources and shared tests touch them: the static `merge(ChatOptions, ChatOptions)`, `copy()`,
`mutate()`, `builder()`, and every getter the model and the properties class read. Only the
supertype wiring differs — 1.1.8 implements the abstract generic `<T extends ChatOptions> T
copy()` and a non-generic `Builder`; 2.0 implements `mutate()` and `Builder<B>` with
`clone()`. The shared tests are the enforcement: they compile and run against both twins, so
a drift in the shared-visible surface breaks the build rather than diverging quietly.

**Test partition.** The 104 tests split by what they exercise, not by package:

| Where | Tests | Which |
|---|---|---|
| Core module | 65 | `ArgumentSpill` 8, `ClaudeCodeCliRequest` 6, `ClaudeCodeCliResponse` 9, `ProcessClaudeCodeCli` 11, `FileSystemFixtureStore` 9, `FixtureKey` 12, `ReplayingClaudeCodeCli` 10 |
| Shared adapter sources | 32 | `ClaudeCodeChatModel` 14, `DefaultConversationRenderer` 7, `ClaudeCodeChatAutoConfiguration` 8, `OneFixtureManyAssertions` 3 |
| Duplicated per adapter | 7 | `ClaudeCodeChatOptions` — it tests the twin, so each module needs its own |

Note `OneFixtureManyAssertionsTests` sits in the `replay` package but drives
`ClaudeCodeChatModel`, so it is an adapter test despite its location and moves with the
shared sources.

Both adapter modules therefore run the same 32 shared tests plus their own 7 — **not** an
"identical suite", since the options tests necessarily differ. That is the acceptance bar in
Validation below.

**Fixtures.** The shared adapter tests use `@TempDir` and record within the test, so they
carry no checked-in fixtures and need no cross-module resource sharing. Should a committed
fixture corpus ever be introduced, it must be published from the core module as a
`test-jar` and consumed by both adapters with `<type>test-jar</type><scope>test</scope>` —
one module's `src/test/resources` is not otherwise visible to another module.

**Auto-configuration.** Both Boot 3.5 and 4.1 use the same
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
mechanism, so each adapter ships its own copy of that file with no structural difference.

**Build.** The parent is `com.iskeru:claudecode-p-parent`, packaging `pom`, published
alongside the others because consumers resolving a child need it. Directory layout mirrors
the artifact names:

```
claudecode-p-parent/            (pom.xml — reactor + shared plugin config)
├── claudecode-cli/             core
├── spring-ai-adapter/          shared sources only; not a module, added via build-helper
├── spring-ai-claudecode-p/     Spring AI 2.0 / Boot 4.1
└── spring-ai-claudecode-p-1x/  Spring AI 1.1 / Boot 3.5
```

All four artifacts share one version and release together; a consumer never has to reason
about which core version pairs with which adapter. The parent declares the reactor and
shared plugin configuration but pins no Spring version — each adapter imports its own BOM.
Java 17 stays the target for all three, being the baseline for both Boot 3.5 and Boot 4.1.

**Validation.** The split is behaviour-preserving. It is done when:

1. The core module's 65 tests pass, with no Spring AI or Spring Boot dependency on its
   compile or test classpath — enforced by `maven-enforcer-plugin`'s `bannedDependencies`,
   so the Spring-free claim cannot rot silently.
2. The 2.0 adapter passes all 39 of its tests (32 shared + 7 own), unchanged in content from
   today's suite apart from package/module moves.
3. The 1.1 adapter passes the same 32 shared tests plus its own 7.
4. Both adapters' auto-configuration tests pass, proving each Boot generation still
   contributes the model bean.

Total across the reactor is 104 + 7 = 111, the increase being the duplicated options tests.

## Known Gaps

- **Unverified beyond the table above.** Only the types the code touches today were
  compared, and only across the two Spring **AI** jars. `ChatResponseMetadata` /
  `ChatGenerationMetadata` builder surfaces and `DefaultUsage` constructors are *assumed*
  compatible and will be confirmed by compiling the legacy module — the first build is the
  test.
- **The Spring Boot 3.5 → 4.1 boundary is unverified.** Calling
  `ClaudeCodeChatAutoConfiguration` and `ClaudeCodeChatProperties` "identical" rests on the
  annotation and auto-configuration surfaces being unchanged across a Boot **major**
  version. That was not checked with `javap` the way the Spring AI delta was. If
  `@ConditionalOn*`, `@ConfigurationProperties` binding, or the `AutoConfiguration.imports`
  contract moved, those two files join `ClaudeCodeChatOptions` as duplicated — which changes
  effort, not the design.
- **`RenderedPrompt` and the renderer belong to the adapter, not the core.** They handle
  Spring AI `Message` types, so they sit in the shared adapter sources with
  `ConversationRenderer`. This matters for spec 001, whose media work spans both sides of
  the split: the `MediaAttachment` type, the fixture key, and side-car storage are core,
  while media *collection* from `Message` is adapter. 001's Known Gaps records the same
  seam from its side.
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
- **This spec is built first, before 001.** The order is settled. This is a
  behaviour-preserving refactor that the existing 104 tests already verify, so doing it on
  today's smaller codebase is the lower-risk sequence; spec 001 is then written against the
  three-module structure and lands once, in its final home. `Media` and `MediaContent` sit
  in the same package in both Spring AI versions, so the split does not constrain what 001
  can do afterwards.

  Practical consequence: this spec is built against the codebase *without* media support,
  so the module contents listed above are complete as written — no placeholder is needed for
  001's types.
## Implementation Notes

Built on `moacyrricardo/spec-002-three-module-split`, PR #1. The split is behaviour-preserving
and landed as written; four things differed.

### A second class had to be duplicated: `ClaudeCodeUsage`

The spec says "Exactly one class is duplicated: `ClaudeCodeChatOptions`." That turned out to be
one short, for the reason its own Known Gaps predicted — `DefaultUsage` was assumed compatible
and was not. `javap` on both jars:

| Member | 1.1.8 | 2.0.0 |
|---|---|---|
| `DefaultUsage(Integer, Integer, Integer, Object, Long, Long)` | absent | present |
| `Usage.getCacheReadInputTokens()` / `getCacheWriteInputTokens()` | absent | present |

`ClaudeCodeChatModel#toUsage` used the six-argument constructor, so the shared model could not
compile on 1.1. Duplicating a 250-line model to save a five-line factory would have defeated the
design, so construction moved behind `ClaudeCodeUsage` — a second per-adapter twin whose entire
surface is `static Usage of(int, int, ClaudeCodeCliResponse.Usage, long, long)` — and `toUsage`
now returns `Usage` rather than `DefaultUsage`. The 1.1 twin folds cache tokens into the prompt
count exactly as 2.0 does and leaves the individual counts reachable via `getNativeUsage()`.

Consequently two assertions moved: `ClaudeCodeChatModelTests.mapsUsageWithCacheTokensFolded...`
keeps the portable prompt/completion/total assertions, and the two cache-getter assertions became
a per-module `ClaudeCodeUsageTests` — the 2.0 one asserting the typed getters, the 1.1 one
asserting the same numbers through the native usage. This is the only behaviour a consumer can
observe differing between the adapters, and it is pinned on both sides.

Everything else the spec listed as identical really was. The two questions it left open both
resolved in favour of sharing: `ClaudeCodeChatAutoConfiguration` and `ClaudeCodeChatProperties`
needed no change across the Boot 3.5 → 4.1 major, and the `ChatResponseMetadata` /
`ChatGenerationMetadata` builder surfaces are identical. The legacy module compiled on its first
build.

### `RecordingFakeCli` forced the core to publish a test-jar

The spec anticipated the mechanism for a future fixture corpus; it was needed immediately.
`RecordingFakeCli` fakes the core's own `ClaudeCodeCli` but is driven by the core's tests *and*
both adapters' tests, and one module's test sources are invisible to another. `claudecode-cli`
therefore publishes a `test-jar`, consumed by both adapters as
`<type>test-jar</type><scope>test</scope>`.

### The test count in Validation was wrong

The spec's "Total across the reactor is 104 + 7 = 111" undercounts: its own Validation items 2
and 3 require the 32 shared tests to run in **both** adapters, which makes the figure it
describes 65 + (32+7) + (32+7) = **143**. Observed: **153** — 65 core + 44 per adapter. The extra
10 are guards added with the split, 4 shared (so ×2) plus 1 per module:

- `ClaudeCodeChatOptionsTwinContractTests` (3, shared) — pins the surface the two options twins
  must keep identical, including the parts only a consumer touches, which shared code would not
  fail to compile over.
- `AutoConfigurationImportsTests` (1, shared) — each module ships its own
  `AutoConfiguration.imports`, because resources are not shared; losing it is invisible to the
  `ApplicationContextRunner` tests, which register the configuration class explicitly.
- `ClaudeCodeUsageTests` (1 per module) — the cache token assertions described above.

The `bannedDependencies` enforcer rule of Validation item 1 was verified the same way rather
than assumed: declaring `spring-core` in the core module fails the build at `validate`.

### Live tests stayed in the 2.0 adapter only

Shared test sources compile into both modules, so `@Tag("live")` tests placed there would double
the subscription spend of every `mvn test -Plive` run. `ClaudeCodeChatModelLiveTests` and
`RecordReplayLiveTests` live in `spring-ai-claudecode-p`. The 1.x adapter therefore has no live
proof — a narrower instance of the "no cross-version integration test" gap already recorded
above. Its 44 offline tests, including the auto-configuration ones, are the proxy.

### Not done here

`spring-ai-adapter` has no POM by design, so IDEs index it only through the two modules that
add it as a source root. Release/CI for four coordinated artifacts remains undecided and
unautomated (the repo has no CI workflow), and Spring AI 1.0.x support and the core's package
rename stay deferred, as the spec's Known Gaps set out.
