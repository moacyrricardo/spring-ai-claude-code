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
Neither string appeared in the accompanying prompt. These are historical feasibility
measurements, not current token-count or price guarantees.

The October 2026 audit repeated the experiment on Claude Code 2.1.296: a PNG returned
`IMG-5826`, and an image-only PDF with an independently verified empty text layer returned
`{"code":"DOC-9043"}` under a JSON schema. The codes appeared only in the attachments.
This proves the CLI route is feasible; the Java adapter still rejects attachments.

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
media is actually present. Keep the text-only transport and fixture keys established by
003 stable. Map structured output explicitly for both transports.

**Dependencies:** 002 is complete; 003 must land before this implementation. The order is
**003 → 001 → 004**. Spec 003 owns isolation, process cleanup, and concurrent recording;
004 evaluates extraction accuracy on a representative corpus. This spec delivers native
PDF/image input and replay, without introducing an OCR engine.

The stream-json input flag forces `--output-format stream-json`, which forces `--verbose`, turning the
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
For a media-only message, omit the empty text block rather than sending an invalid empty
text content block.

The response is NDJSON — one JSON object per line. Parse incrementally while draining
stdout, ignoring valid unknown event types and blank lines. Retain the final
`type: "result"` envelope and bounded diagnostics about event types seen; do not buffer the full
event stream. Parse that envelope through `ClaudeCodeCliResponse.fromJson`, extended for
structured output and error envelopes below. Malformed JSON, non-object events, and a
truncated final line that is not valid JSON are protocol failures, not unknown events to
silently discard. A complete final object without a trailing newline is valid.

**Stream failure modes.** If the stream ends with no result, any result carries an error,
or the process exits non-zero, throw `ClaudeCodeException`. A later success must not hide
an earlier error result. The no-result message names the exit code, bounded captured
stderr, and event types seen. Propagate reader failures through 003's process cleanup.

**Structured output.** Extend `ClaudeCodeCliResponse` with an optional structured JSON
value, preserving the original raw envelope. Prefer serialized `structured_output` as
the Spring assistant text when it is present, and expose it in generation metadata as
`structuredOutput`. Otherwise use the textual `result`. A successful envelope containing
only structured output is valid; a successful envelope with neither field is invalid.
Accept an explicitly empty textual result. Inspect `is_error`, error subtypes, and error
details before requiring success fields, so failures without `result` retain useful
diagnostics. Preserve usage, session, and stop-reason metadata, including `tool_use` when
reported for a structured completion. Do not turn it into a Spring tool callback.

On 2.1.296 the tested CLI copied structured JSON into `result` as well, but the mapping
must not depend on that duplication. Apply the same mapping to JSON and NDJSON transports
and replay. Retain compatibility constructors for existing response callers where needed.

**Request.** `ClaudeCodeCliRequest` gains
`List<MediaAttachment> media`, where `MediaAttachment` is a core-owned immutable value of
`(String mimeType, byte[] data)`. Copy byte arrays on construction and access and copy the
ordered list, so later mutation cannot change the request between hashing and execution.
`prompt` stays a `String`; the CLI layer assembles the content-block array. Default media
to an empty list. Preserve the old request constructor as a delegating overload and retain
existing builder calls. Empty media selects the existing text transport.

**Renderer.** `RenderedPrompt` gains a media list.
`DefaultConversationRenderer.rejectMedia` is replaced by user-media collection. Because a
multi-turn conversation is flattened into one tagged transcript, media from each user turn
attaches to the single rendered user message, in conversation and attachment order.
Reject media on assistant, system, and tool messages with an error naming the role; do
not silently drop it. Allow media-only user messages with empty text. Retain the existing
two-argument `RenderedPrompt` constructor and pass the new list through
`ClaudeCodeChatModel.toCliRequest`.

Support byte-backed media and local file/classpath resources. Inspect the actual Spring AI
`Media` representation in both supported versions before resolving it; do not assume
`getDataAsByteArray()` safely resolves every resource kind. Reject URI-backed media for
this first version and reject network-backed resources before reading them. A remote URL
wrapped in `UrlResource` must not bypass the check. Support an explicit set of local
resource types rather than guessing whether an arbitrary custom `Resource` performs I/O
over the network. Callers with other resource types can provide bytes explicitly.

Read permitted resources with a byte limit rather than eagerly loading unbounded input.
Missing/unreadable files fail with the attachment index and useful cause. Resource paths
and temporary filenames are diagnostic metadata, not semantic fixture identity.

**Where the checks live.** Resource resolution and message-role checks belong in the shared
renderer. MIME, non-empty byte data, attachment count, and byte-limit validation also run
in core request construction, before either replay lookup or process execution. This gives
direct-core and adapter callers the same validity contract. Validation solely inside
`ProcessClaudeCodeCli` is insufficient because replay bypasses it.

**MIME mapping.** `image/png`, `image/jpeg`, `image/gif`, `image/webp` become `image`
blocks; `application/pdf` becomes a `document` block. Anything else fails with an explicit
error naming the type, rather than reaching the CLI and returning an opaque API error.

Normalize MIME casing and omit non-semantic MIME parameters before validation and hashing.
Use one shared mapping for validation, content-block type, and fixture extension. Validate
the declared type; parsing every file format to prove its content type is outside the core.

**Input and output bounds.** For the initial implementation, use documented client limits
of 32 attachments, 5 MiB per attachment, 10 MiB total raw attachment bytes, and 16 MiB for
the complete serialized media stdin message (UTF-8, including base64 and prompt text).
These are library limits, not statements of upstream model limits. Enforce resource limits
during reads and the serialized-size bound before launch; apply the same request validity
checks before replay lookup. Empty bytes and over-limit requests fail locally.

For media output, replace the text capture policy with an 8 MiB maximum event line, a
4 MiB maximum retained result, and a 32 MiB cumulative stdout limit; retain only 003's
bounded stderr tail and event diagnostics.
Count bytes while reading, before allocating an unbounded line. Exceeding any bound fails
and triggers process cleanup, never partial success. Document these initial limits and
measure the live smoke cases against them. PDF page count and model-specific image limits
remain upstream validation; no PDF parser dependency is added to the core.

**Fixture key.** `FixtureKey` folds in each attachment's SHA-256 and MIME type, in order.
This is not optional: without it the same text with two different images collides on one
fixture and silently serves the wrong answer.

**The media fields are appended only when media is present.** `FixtureKey` hashes a
canonical string built by appending one labelled line per field, so appending media lines
unconditionally would change the digest of *every* existing fixture — including
text-only ones — and every committed fixture would miss. Skipping the media lines entirely
for an empty list keeps the canonical string byte-identical to 003's baseline for text-only
requests. A test must pin this: a known text-only request has to produce the same key
before and after this change.

**Fixture storage.** Media is written to `<fixtures>/media/<sha256>.<ext>` and referenced
from the fixture JSON by digest. Identical images across fixtures deduplicate for free.
`FileSystemFixtureStore` publishes side-cars before the fixture file, using temporary files
and atomic replacement where supported, following 003's publication/cleanup rules.
Validate an existing digest-named side-car before reusing it. A failed save must not publish
a fixture referencing an incomplete file. Concurrent different keys can share identical
attachments safely.

The fixture's `request.media` field is an ordered array carrying both the digest and MIME
type, so a fixture is self-describing without opening the side-car:

```json
"media": [ { "sha256": "3f7a…", "mimeType": "image/png", "file": "media/3f7a….png" } ]
```

`<ext>` is derived from the MIME type via the same mapping used for block selection; an
unmapped MIME type is invalid. The `file` field is recorded explicitly rather than
recomputed, so a future extension mapping change cannot orphan existing fixtures.

**Missing or corrupt side-cars are hard failures, not misses.** On load, check each file's
existence and SHA-256 against its recorded digest. Reject absolute paths, traversal, and
symlink escapes outside the fixture media directory. Reject malformed attachment metadata
and unsupported MIME types. Throw in REPLAY and AUTO without invoking the delegate; a
partial checkout must not silently cause a live re-record. Hash contents on load rather
than assuming a matching filename proves integrity.

**Backwards compatibility.** Fixtures written before this change have no `media` field.
The loader treats absent as empty. This media change causes no additional text-only
fixture migration beyond the explicit isolation-policy migration in 003.

**Validation.** The spec is done when all of the following hold:

1. `mvn clean verify` passes across the core and both adapters, including the audit's
   153-test baseline and the regression coverage added by 003. Replace the interim
   user-media rejection test with forwarding coverage; retain unsupported-role rejection.
2. A key-stability test proves a known text-only request hashes to the same
   `FixtureKey` before and after this change, using 003's post-isolation baseline.
3. A live-tagged integration test (`@Tag("live")`, so it runs only under `mvn test -Plive`)
   sends a checked-in PNG and an image-only PDF with a verified empty text layer, each
   containing a string absent from the prompt, and asserts the returned value. It runs
   in `auto` mode against a `@TempDir`, so the same test records and then replays.
4. That test's second pass, replaying from the recorded fixture with **no CLI delegate
   configured**, returns identical text — proving media survives the fixture round trip
   rather than only the live call.
5. Different attachment bytes, MIME types, counts, and order produce different fixture
   keys. Identical bytes supplied through different local resources produce the same key.
   Mutation of source arrays or returned arrays cannot change a built request or its key.
6. Stub-process tests verify exact input blocks and escaping, media-only messages, multiple
   attachments, final result selection, valid unknown events, malformed/truncated NDJSON,
   missing results, error results, non-zero exits, and output-limit cleanup.
7. Both adapters test byte/classpath/file media, rejected remote URI and remote-resource
   input without a fetch, unsupported roles/MIME types, empty bytes, and input boundaries.
   Direct-core requests receive the same MIME/byte validation in LIVE, AUTO, and REPLAY.
8. Fixture tests cover side-car deduplication, failed publication, missing/corrupt content,
   path escapes, and legacy text-only fixtures. AUTO must not invoke its delegate on a
   corrupt fixture. Removing the original source file does not prevent replay when the
   caller supplies the same bytes and the side-cars remain intact.
9. Structured-response tests cover structured-only success, differing structured/text
   fields, ordinary text, errors without `result`, and exact metadata/raw-envelope replay.
   A live scanned-PDF test extracts a schema-conforming field and replays it offline.
10. Add a shared test-resource root with `build-helper-maven-plugin:add-test-resource` in
    both adapters for shared sample media. Keep live tests and their live-only assets in
    the 2.0 module so ordinary `-Plive` does not spend twice. Document a working
    `UserMessage` attachment example for both supported Spring AI generations.

## Known Gaps

- **Streaming is unaffected.** `stream()` remains a single-element `Flux`; media does not
  change that.
- **Only user-turn attachments are supported.** Other roles fail explicitly.
- **Multi-turn attribution is lossy.** Flattening means the model sees all attachments on
  one turn rather than bound to the turn they arrived on. A custom renderer can annotate
  the transcript, but native per-turn media attribution needs a future request design.
- **Upstream limits still apply.** Client byte limits do not guarantee acceptable page
  count, image dimensions, encryption, or file validity. Surface CLI rejection clearly.
- **CLI protocol can change.** The stream-json companion flags were verified empirically;
  the live tests guard the supported CLI contract established in 003.
- **OCR quality is not established here.** No searchable-PDF generation, bounding boxes,
  OCR confidence, preprocessing engine, or accuracy guarantee; those needs are evaluated
  in 004 rather than inferred from a successful attachment round trip.
- **No remote media fetching or side-car garbage collection.** Local bytes/resources are
  the first supported input contract; unreferenced side-cars may remain after re-recording.
