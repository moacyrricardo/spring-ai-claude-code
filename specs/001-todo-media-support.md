# 001 — Media support (images and documents)

## Context

`ClaudeCodeChatModel` renders only `Message.getText()`. Spring AI's `UserMessage`
implements `MediaContent`, so an attachment is accepted by the API and then dropped: the
model answers from the surrounding text and the test passes without ever having exercised
the image. For test infrastructure that is the worst available outcome — a green
assertion that proves nothing.

An interim guard now rejects non-empty `getMedia()` in `DefaultConversationRenderer`, so
today's behaviour is at least honest. This spec covers actually forwarding it.

Feasibility is settled, not assumed. Both cases were verified against the real CLI:

| Input | Content block | Result |
|---|---|---|
| PNG with `CAT-7391` drawn in it | `image` / base64 | returned `CAT-7391` (323 input tokens) |
| PDF containing `DOC-4417` | `document` / `application/pdf` | returned `DOC-4417` |

The PDF had no text layer, so that is genuine document vision rather than text extraction.
Neither string appeared in the accompanying prompt.

A file-path alternative — write media to a temp file and let the CLI's `Read` tool fetch
it — was measured and rejected. Same image, same question:

| Route | Input context | Turns | Cost |
|---|---|---|---|
| base64 content block | 323 | 1 | $0.0017 |
| temp file + `Read` tool | 3,484 | 2 | $0.0101 |

It costs ~6× more because image tokens are computed from pixel dimensions either way — the
base64 string is decoded before tokenization and is never itself billed — while the tool
route adds the agent scaffolding and a second inference pass. It is also non-deterministic
(the model *chooses* whether to call `Read`) and re-opens filesystem access, both
disqualifying for a test harness.

## Decision

Forward media as base64 content blocks over `--input-format stream-json`, and only when
media is actually present. The existing text path is left exactly as it is.

That flag forces `--output-format stream-json`, which forces `--verbose`, turning the
response from a single JSON object into an NDJSON event stream. Media is therefore a
**second transport**, not a parameter — which is why the text path stays untouched: its
one-turn envelope and its committed fixtures keep working unchanged.

Fixtures store media as digest-named side-car files rather than inline base64, preserving
the readable-diff property the replay design exists for.

## Implementation

**Transport.** `ProcessClaudeCodeCli` gains a media-bearing branch that adds
`--input-format stream-json --output-format stream-json --verbose` and writes exactly one
JSON object, followed by a newline, to stdin. All three flags are required together: the
CLI rejects `--input-format stream-json` without stream-json output, and rejects
stream-json output under `--print` without `--verbose`.

The stdin object, as verified:

```json
{"type":"user","message":{"role":"user","content":[
  {"type":"image","source":{"type":"base64","media_type":"image/png","data":"<base64, no newlines>"}},
  {"type":"text","text":"<the rendered user prompt>"}]}}
```

A document block substitutes for the image block and is otherwise identical:

```json
{"type":"document","source":{"type":"base64","media_type":"application/pdf","data":"<base64>"}}
```

**Block order is media first, then one text block last.** That ordering is what the
feasibility runs used; it also matches Anthropic's documented guidance for document input.

The response is NDJSON — one JSON object per line. The reader parses each line and keeps
the single object whose `type` is `"result"`; that object has the same shape as today's
`--output-format json` envelope (`result`, `session_id`, `usage`, `total_cost_usd`,
`is_error`, `subtype`), so `ClaudeCodeCliResponse.fromJson` consumes it unchanged. Lines
that fail to parse are skipped rather than fatal, since the stream carries event types this
code does not model.

**Stream failure modes.** If the stream ends with no `type: "result"` line, or the result
line carries `is_error`, the call throws `ClaudeCodeException` — the same contract the
current transport already has for an error envelope. The no-result message names the exit
code, captured stderr, and the `type` values actually seen, because a silent empty result
is indistinguishable from a model that answered nothing.

**Request.** `ClaudeCodeCliRequest` gains
`List<MediaAttachment> media`, where `MediaAttachment` is a record of
`(String mimeType, byte[] data)`. `prompt` stays a `String`; the CLI layer assembles the
content-block array. Empty media means the current transport, byte for byte.

**Renderer.** `RenderedPrompt` gains a media list.
`DefaultConversationRenderer.rejectMedia` is replaced by collection. Because a multi-turn
conversation is flattened into one tagged transcript, media from *any* turn attaches to the
single rendered user message, in conversation order.

Spring AI's `Media` may hold either a `Resource` or a `URI`. Collection resolves it through
`Media#getDataAsByteArray()`, which covers the `Resource` case — a `ClassPathResource` is
the normal way a test supplies a fixture image, and reading it costs no network. A
`Media` backed by a remote `URI` is **rejected** with a named error rather than fetched:
fetching would make a test depend on network availability and on content that can change
underneath a recorded fixture, which is the determinism the replay layer exists to protect.

**Where the checks live.** Both the MIME check and the remote-`URI` check live in the
renderer, not in the CLI layer. Putting them in the CLI layer would let replay mode serve a
fixture for input that record mode rejects, so the two modes would disagree about what is
valid.

**MIME mapping.** `image/png`, `image/jpeg`, `image/gif`, `image/webp` become `image`
blocks; `application/pdf` becomes a `document` block. Anything else fails with an explicit
error naming the type, rather than reaching the CLI and returning an opaque API error.

**Fixture key.** `FixtureKey` folds in each attachment's SHA-256 and MIME type, in order.
This is not optional: without it the same text with two different images collides on one
fixture and silently serves the wrong answer.

**The media fields are appended only when media is present.** `FixtureKey` hashes a
canonical string built by appending one labelled line per field, so appending media lines
unconditionally would change the digest of *every* existing fixture — including
text-only ones — and every committed fixture would miss. Skipping the media lines entirely
for an empty list keeps the canonical string byte-identical to today's for text-only
requests. A test must pin this: a known text-only request has to produce the same key
before and after this change.

**Fixture storage.** Media is written to `<fixtures>/media/<sha256>.<ext>` and referenced
from the fixture JSON by digest. Identical images across fixtures deduplicate for free.
`FileSystemFixtureStore` writes side-cars before the fixture file, so a fixture never
references a file that does not exist.

The fixture's `media` field is an ordered array carrying both the digest and the MIME type,
so a fixture is self-describing without opening the side-car:

```json
"media": [ { "sha256": "3f7a…", "mimeType": "image/png", "file": "media/3f7a….png" } ]
```

`<ext>` is derived from the MIME type via the same mapping used for block selection, with
`.bin` for anything unmapped. The `file` field is recorded explicitly rather than
recomputed, so a future change to the extension mapping cannot orphan existing fixtures.

**A missing side-car is a hard failure, not a miss.** If a fixture references a digest whose
file is absent, the store throws rather than reporting a fixture miss. A miss in `replay`
mode is already a failure, but in `auto` mode it would silently trigger a live re-record —
turning a partial checkout into unexplained token spend.

**Backwards compatibility.** Fixtures written before this change have no `media` field.
The loader treats absent as empty, so every committed fixture keeps replaying and no
re-recording is required.

**Validation.** The spec is done when all of the following hold:

1. The existing 104-test suite passes unchanged — media is additive, and no text-only
   behaviour may shift.
2. A key-stability test proves a known text-only request hashes to the same
   `FixtureKey` before and after this change.
3. A live-tagged integration test (`@Tag("live")`, so it runs only under `mvn test -Plive`)
   sends a checked-in PNG and a checked-in PDF, each containing a string that appears
   nowhere in the accompanying prompt, and asserts the model returns that string. It runs
   in `auto` mode against a `@TempDir`, so the same test records and then replays.
4. That test's second pass, replaying from the recorded fixture with **no CLI delegate
   configured**, returns identical text — proving media survives the fixture round trip
   rather than only the live call.
5. Two requests differing only in their attached image produce different fixture keys.

## Known Gaps

- **Streaming is unaffected.** `stream()` remains a single-element `Flux`; media does not
  change that.
- **No media in assistant or tool messages.** Only user-turn attachments are forwarded.
- **Multi-turn attribution is lossy.** Flattening means the model sees all attachments on
  one turn rather than bound to the turn they arrived on. A custom `ConversationRenderer`
  can do better if it matters.
- **Size limits are the API's.** No client-side check on request size or page count; an
  oversized payload surfaces as a CLI error.
- **`--input-format stream-json` is an undocumented-in-`--help` dependency chain.** Its two
  forced companion flags were discovered empirically and could change between CLI releases;
  the live media test in Validation is what will catch that.
- **This spec is built second, after 002.** The order is settled: 002 splits the project
  into three modules first, and this work is then written against that structure rather than
  against a single artifact. So the changes here land on both sides of the split from the
  start — `ClaudeCodeCliRequest`, `FixtureKey`, `FileSystemFixtureStore` and
  `ProcessClaudeCodeCli` in the core module; `RenderedPrompt` and
  `DefaultConversationRenderer` in the shared adapter sources. Nothing has to be moved
  afterwards, and the media integration test can be placed once, in its final module.

  This ordering is a dependency, not a preference: an implementer must not start this spec
  while 002 is unmerged.
