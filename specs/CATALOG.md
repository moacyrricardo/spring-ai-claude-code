# Catalog

| #   | Status | Title / slug             | Branch | Issue | Notes |
|-----|--------|--------------------------|--------|-------|-------|
| 001 | todo   | media-support            | —      | —     | Depends on 002 and 003; images + PDFs, structured output, bounded transport, attachment-aware replay |
| 002 | done   | three-module-split       | moacyrricardo/spec-002-three-module-split | — | Spring-free core + Spring AI 2.0 and 1.1 adapters; targets 1.1.8 (no Spring AI 1.4 exists) |
| 003 | done   | runtime-reliability-isolation | moacyrricardo/spec-003-runtime-reliability-isolation | — | PR #2; 186 offline tests on Java 17/25 + 6 live tests; isolation, concurrent recording, process cleanup, provider selection |
| 004 | todo   | document-extraction-validation | — | — | Depends on 003 and 001; corpus, live accuracy evaluation, offline regression, evidence-based OCR decision |

Implementation order: **003 → 001 → 004**. Spec 002 is complete. Dependencies are
recorded in each spec; numbering is permanent and does not imply execution order.
