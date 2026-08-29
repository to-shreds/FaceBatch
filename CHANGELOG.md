# Change log

## Web 1.0.0 snapshot, 2026-08-29

- Added a single-file browser implementation of the current FaceBatch workflow.
- Preserved single-face donor-by-target cross-product batching.
- Preserved built-in engine selection and the Android-style custom API profile.
- Added multi-face donor sharing, target recipe rows, analysis, sparse assignments, duplication, auto-advance, retry, and sequential execution.
- Added genuine JPEG conversion and no-dependency ZIP export.
- Added IndexedDB draft restoration and local settings persistence.
- Added a private Node gateway with AIFaceSwap, Face Over Auto, FJoy/Magicut, TaoAnhDep, and custom adapters.
- Added a public `docs/` artifact for GitHub Pages with no provider or signing credentials.
- Added local mock tests, browser workflow tests, responsive screenshots, integrity validation, and GitHub workflows.
- Added preservation records for the Android 0.7.0 release and sanitized 0.4.0 source.

## Android 0.7.0, 2026-08-27

- Expanded donor thumbnails into a full-width, vertically growing grid.
- Kept every donor visible through the main screen scroll.
- Moved donor A/B/C labels below the image boxes.
- Added the live face-selection prompt.
- Advanced donor assignment automatically to the next unassigned face.
- Advanced completed rows to the next unfinished target while preserving intentional edits to completed rows.
- Applied the same navigation behavior to Auto Assign and Same Donor.
- Preserved sparse mappings and manual face selection.

## Android 0.6.0, 2026-08-26

- Added up to 100 ordered multi-face recipe rows.
- Added multiple targets, repeated versions of one target, shared donors, row copying, stable output numbering, continuation after failures, and retry-failed rows.
- Added stale face-analysis refresh, normalized geometry reconciliation, and fail-closed mapping behavior.

## Android 0.5.0, 2026-08-26

- Added live-validated AIFaceSwap multi-face analysis and generation.
- Added sparse target-index mappings, exact nonce generation, JPEG normalization, overlay markers, assignment shortcuts, progress, cancellation, and errors.

## Android 0.4.0, 2026-08-23

- Added the high-quality AIFaceSwap route and preserved existing alternatives.
- Preserved the compact cross-product batch workflow and real JPEG output.
