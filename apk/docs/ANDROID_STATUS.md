# Android development status

## Functional line

FaceBatch evolved from a configurable donor-by-target batch client into a high-quality single and multi-face workflow:

- 0.1.x established multi-file and recursive folder selection, cross-product batching, retries, cancellation, background processing, output saving, and custom API mappings.
- 0.3.x reconstructed Face Over alternatives, serialized unstable providers, rotated FJoy sessions, and converted WebP results to genuine JPEG.
- 0.4.0 added the AIFaceSwap high-quality route.
- 0.5.0 added live-validated AIFaceSwap multi-face analysis and generation.
- 0.6.0 added one or many target recipe rows, duplicated versions, shared donors, stable sequential execution, persistence, geometry reconciliation, and retry-failed rows.
- 0.7.0 refined the editor so donors remain visible, labels no longer cover faces, and donor and row selection advance automatically.

## Current recovery state

The 0.7.0 release record is complete enough to identify authentic artifacts and guide a source recovery. The exact source bytes are still needed before a new Android release can be responsibly compiled. The 0.4.0 source archive is useful for protocol and architecture continuity, but it predates the multi-face model and 0.7.0 user interface.

## Next legitimate Android version

After restoring and verifying the 0.7.0 source, the next Android build must use:

- package `com.jon.facebatch`;
- the same personal signing certificate;
- version code at least 14;
- no unrelated redesign or package rename.

Run all existing model, editor-flow, ZIP, alignment, signature, and DEX checks before distribution. A physical-device update and live provider smoke test remain required.
