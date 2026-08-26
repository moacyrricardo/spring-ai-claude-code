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
`--input-format stream-json --output-format stream-json --verbose`, writes one JSON message
object to stdin, and selects the `type: "result"` line from the NDJSON response.
`ClaudeCodeCliResponse.fromJson` is unchanged; a new reader picks the result line out of the
stream and hands it over.

**Request.** `ClaudeCodeCliRequest` gains
`List<MediaAttachment> media`, where `MediaAttachment` is a record of
`(String mimeType, byte[] data)`. `prompt` stays a `String`; the CLI layer assembles the
content-block array. Empty media means the current transport, byte for byte.

**Renderer.** `RenderedPrompt` gains a media list.
`DefaultConversationRenderer.rejectMedia` is replaced by collection. Because a multi-turn
conversation is flattened into one tagged transcript, media from *any* turn attaches to the
single rendered user message, in conversation order.

**MIME mapping.** `image/png`, `image/jpeg`, `image/gif`, `image/webp` become `image`
blocks; `application/pdf` becomes a `document` block. Anything else fails with an explicit
error naming the type, rather than reaching the CLI and returning an opaque API error.

**Fixture key.** `FixtureKey` folds in each attachment's SHA-256 and MIME type, in order.
This is not optional: without it the same text with two different images collides on one
fixture and silently serves the wrong answer.

**Fixture storage.** Media is written to `<fixtures>/media/<sha256>.<ext>` and referenced
from the fixture JSON by digest. Identical images across fixtures deduplicate for free.
`FileSystemFixtureStore` writes side-cars before the fixture file, so a fixture never
references a file that does not exist.

**Backwards compatibility.** Fixtures written before this change have no `media` field.
The loader treats absent as empty, so every committed fixture keeps replaying and no
re-recording is required.

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
  the media integration test is what will catch that.
