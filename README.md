# FaceBatch status snapshot

This repository is a recovery-grade snapshot of the FaceBatch project as of **August 29, 2026**. It preserves the latest verified Android release record, a sanitized Android source baseline, the new browser implementation, a private gateway, validation evidence, and publishing automation.

## Start here

- **Browser application:** [`web/FaceBatch.html`](web/FaceBatch.html)
- **GitHub Pages artifact:** [`docs/index.html`](docs/index.html)
- **Feature-parity matrix:** [`web/FEATURE_PARITY.md`](web/FEATURE_PARITY.md)
- **Private gateway:** [`web/gateway/server.mjs`](web/gateway/server.mjs)
- **Android status:** [`apk/README.md`](apk/README.md)
- **Research and development summary:** [`research/RESEARCH_AND_DEVELOPMENT.md`](research/RESEARCH_AND_DEVELOPMENT.md)
- **Validation report:** [`web/tests/TEST_REPORT.md`](web/tests/TEST_REPORT.md)
- **Publishing instructions:** [`PUBLISHING.md`](PUBLISHING.md)
- **Complete machine-readable inventory:** [`manifests/SNAPSHOT.json`](manifests/SNAPSHOT.json)

## What this snapshot contains

### `apk/`

The controlling Android release is **FaceBatch 0.7.0**, package `com.jon.facebatch`, version code 13. The original 0.7.0 binary and raw source archive were not available in the active build workspace, so this repository preserves their known cryptographic identities, complete release history, behavior, signing certificate fingerprint, and reconstruction instructions. It also includes a sanitized copy of the available 0.4.0 source baseline.

No signing key or signing password is committed. This is intentional and necessary for security. Future Android releases must use the separately retained personal signing key to preserve update compatibility.

### `web/`

`FaceBatch.html` is a single-file browser application with its HTML, CSS, JavaScript, interface, persistence, batch logic, image preparation, JPEG conversion, ZIP writer, and diagnostics embedded in one file. It includes:

- donor-by-target single-face cross-product batching;
- multiple image and folder selection;
- bounded concurrency, retries, cancellation, and retry-failed behavior;
- one or many multi-face target rows with shared donor images;
- up to 20 donors and 100 committed recipe rows;
- sparse face mappings and manual face selection;
- donor and target-row auto-advance behavior from Android 0.7.0;
- stale-analysis refresh and normalized geometry reconciliation;
- IndexedDB draft persistence;
- individual JPEG downloads and a no-library ZIP download;
- built-in engine selection and the complete custom endpoint, headers, fields, response-path, and polling profile.

### `web/gateway/`

A static GitHub Pages site cannot securely hold provider protocol details or defeat browser cross-origin restrictions. The included Node gateway keeps network adapters and restricted headers out of the public page. The HTML remains usable as a single file, but live built-in-provider generation requires an HTTPS deployment of this gateway or another compatible gateway.

## Snapshot posture

This is a preservation release, not a claim that every external provider remains stable forever. Third-party endpoints can change without notice. The browser interface and mock integration tests pass. The original Android 0.7.0 validation evidence is preserved, but no replacement 0.7.0 APK was fabricated from an older source tree.

## Quick local browser test

```bash
cd web/gateway
FACEBATCH_MOCK=1 \
FACEBATCH_GATEWAY_TOKEN=dev-token \
FACEBATCH_ALLOWED_ORIGINS=http://127.0.0.1:8765 \
node server.mjs
```

In another terminal:

```bash
python -m http.server 8765 --bind 127.0.0.1 --directory web
```

Open `http://127.0.0.1:8765/`, set the gateway to `http://127.0.0.1:8787`, use token `dev-token`, and test without contacting any external provider.

## Integrity

Run:

```bash
python scripts/validate_snapshot.py
bash web/tests/gateway-smoke.sh
bash web/tests/custom-adapter-smoke.sh
python web/tests/browser_smoke.py
```

The root [`SHA256SUMS`](SHA256SUMS) and manifest allow later verification and comparison.
