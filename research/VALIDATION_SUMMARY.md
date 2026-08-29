# Validation summary

## Android evidence preserved

The original 0.7.0 release record reports successful resource compilation, Java compilation, D8 conversion, 27 editor-flow assertions, ZIP integrity, alignment, signature verification under v1, v2, and v3, and compiled DEX inspection. It also records the absence of a separate physical-device UI run for that UI-only release.

The 0.6.0 report records a passing multi-face model test and an 18-pass persistence and edge harness. The 0.5.0 report records a live AIFaceSwap multi-face protocol smoke test.

## Web evidence executed

- JavaScript syntax validation
- Node gateway syntax validation
- mock authentication and CORS behavior
- mock session, analysis, single, and multi endpoints
- Chromium single and multi workflows
- retries, results, JPEG conversion, and ZIP integrity
- IndexedDB restoration
- 20-donor mobile layout
- custom immediate, JSON, fallback, URL, polling, header, and form-field modes
- snapshot secret scan and file integrity

Detailed results are in `web/tests/TEST_REPORT.md`.
