# 004 — Document extraction validation

## Context

Spec 001 proves that attachment bytes reach Claude and replay correctly. That does
not establish whether the model extracts useful information from realistic scans,
tables, or multipage documents. Recorded answers can keep tests green while hiding
changes in model quality.

The audit read a code from an image-only PDF and returned schema-based JSON on
Claude Code 2.1.296. That is transport feasibility evidence, not an accuracy study.
No representative user-document corpus or business field contract has been supplied.

## Decision

Build a small, reproducible document-extraction evaluation harness after 001. Keep
offline pipeline regression tests separate from live accuracy evaluation. Start
with native PDF/image understanding, measure it against independently authored
ground truth, and use the results to decide whether OCR preprocessing is warranted.

**Dependencies:** 003 and 001 must be complete. This spec defines the evaluation
deliverable now; corpus-specific results are produced during implementation.

## Implementation

### Corpus and ground truth

Start with at least 12 checked-in synthetic cases, with at least two cases covering
each category (categories may overlap): text-layer PDFs, clean image-only scans,
degraded/rotated scans, multipage documents, tables, and missing/unreadable fields.
Include PNG/JPEG input as well as PDF. Include at least one pair with identical
prompt text and different document values. Keep document sizes within 001 limits.

Place the corpus and manifest under
`spring-ai-claudecode-p/src/test/resources/document-extraction/`. Live evaluation
runs only in this adapter; shared contract tests use the shared test-resource
mechanism introduced by 001. Use small synthetic purchase invoices as the initial
domain so the harness is buildable before user samples arrive.

Each manifest entry specifies a stable case ID, relative source path, source
SHA-256, categories, expected JSON, and fields deliberately absent or unreadable.
Derive expected values from the source-generation data or a manual transcription,
never from a model answer. For scans, verify the PDF has no extractable text layer.
Store generation instructions and normalization rules alongside the corpus.

Use this initial field contract:

- `documentId`, `issuer`, `date` (ISO date or null), and `currency` (code or null).
- `total` as an exact decimal string or null.
- `lineItems` as an ordered array of description, quantity, and amount fields;
  numeric values use decimal strings and unknown values use null.

Require all top-level keys and reject additional properties in the JSON schema.
Prompts explicitly require null for unknown values and forbid guessing. Include
cases whose correct answer contains nulls. Define array ordering, whitespace
normalization, and decimal comparison in the evaluator, not ad hoc in assertions.

Real user documents can be added with independent ground truth once supplied.
Synthetic-only results must be labeled as such and cannot establish user-corpus
readiness. Keep the corpus version and its digest in every evaluation result.

### Two execution paths

**Offline regression:** fixtures exercise prompt construction, media identity,
structured-response mapping, downstream parsing, and deterministic assertions.
REPLAY has no delegate and a missing fixture fails. Run these checks in default CI.
Separate ground truth from recorded responses; never overwrite expected values
while updating recordings.

**Live evaluation:** introduce a `document-eval` Maven profile selecting tests
tagged both `live` and `document-eval`. Exclude `document-eval` from the routine
`live` profile so adding a corpus does not multiply its smoke-test spend. Document
`mvn test -Pdocument-eval` as the explicit evaluation command. Verify tag filtering
in all modules; no duplicate evaluations in the 1.x adapter.

Evaluate every selected case three times with live delegate calls, never AUTO or
REPLAY. Capture each run as a distinct artifact keyed by run ID, case ID, and repeat
number so repeats do not overwrite each other. Keep this capture separate from
normal request-keyed regression fixtures.

Require an explicit full model ID, record the serving model, and flag fallback to
a different model. Record CLI version, isolation policy, prompt/schema hashes,
corpus digest, raw result envelope, parsed answer, latency, token counts, and CLI
cost estimate. Cost is an estimate from the envelope, not subscription billing.

### Scoring and output

Produce machine-readable results and a concise Markdown summary under
`target/document-evaluation/<run-id>/`. Include per-case/per-repeat outcomes,
per-field accuracy, critical-field accuracy, document-level exact match, null-field
hallucination count, schema-validity rate, and latency/token/cost summaries.

Use `documentId`, `currency`, and `total` as the initial critical fields. Only
normalize whitespace and declared numeric/date representations; do not silently
correct identifier characters, infer missing values, or reorder table rows to
improve scores. Missing or extra rows count as mismatches. Include failed calls in
the denominator and distinguish transport errors from extraction errors.

Initial acceptance targets for the curated corpus, across all repeats:

- 100% schema-valid responses and no transport failures.
- 100% correct critical fields on cases whose ground truth is legible.
- At least 95% exact field matches using the declared scoring rules.
- Zero invented non-null values for fields marked absent or unreadable.

Report category-level results as well as aggregates. These are explicit initial
targets for this corpus, not a statistical guarantee for arbitrary documents.
Retain repeat-level variance rather than scoring only the best answer.

### OCR decision

Classify failures before proposing preprocessing:

- Transport/size/framing failures return to 001 or 003.
- Text recognition failures on degraded scans motivate deskewing, rendering, or
  OCR evaluation.
- Table associations or field-selection failures motivate prompt/schema or layout
  handling changes; OCR alone may not address them.

Write an evidence-based recommendation with the evaluation results. If native
vision misses the targets, mark document acceptance as failed and propose a
separate spec for a measured PDFBox/Tesseract/OCRmyPDF preprocessing experiment.
Include extraction engine/version/settings in any future preprocessing identity.
Do not silently substitute pre-extracted text for a test intended to exercise
native PDF vision.

### Validation

1. At least 12 manifest-backed cases have independently reviewed ground truth,
   verified source hashes, and the required category coverage.
2. Evaluator tests cover incorrect identifiers, missing/extra table rows, decimal
   normalization, null hallucinations, malformed JSON, and failed invocations.
3. Offline CI succeeds with no Claude executable and no delegate. A missing fixture
   fails instead of spending tokens; both adapters retain their shared media tests.
4. An explicit live evaluation produces three independent calls per selected case,
   with complete provenance and repeat-level scores. Routine `mvn test` and
   `mvn test -Plive` do not accidentally execute the document-eval corpus.
5. Results clearly distinguish transport success, extraction acceptance, and
   synthetic versus real-corpus coverage. A below-threshold result fails the
   evaluation command and remains visible in the saved summary.
6. The implementation notes link a baseline summary, record whether the targets
   passed, and state the next action. The evaluation harness can be delivered with
   a failed quality baseline; that must not be described as document acceptance.

## Known Gaps

- Real-document acceptance requires user samples and agreed business fields. The
  initial invoice schema and thresholds can be revised explicitly before those
  evaluations; do not adjust them after observing scores just to obtain a pass.
- Searchable PDF generation, word bounding boxes, calibrated OCR confidence,
  handwriting guarantees, and bulk production ingestion are outside this spec.
- Three repetitions reveal obvious instability but are not a statistically powered
  reliability study. A larger corpus may be required for the eventual application.
- Model-supplied confidence is not treated as a calibrated accuracy measure.
- No separate OCR runtime dependency is added by this evaluation work.
