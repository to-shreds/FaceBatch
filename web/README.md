# FaceBatch Web

## Standalone file

[`FaceBatch.html`](FaceBatch.html) is the canonical one-file application. [`index.html`](index.html) and [`../docs/index.html`](../docs/index.html) are byte-identical publishing copies.

No external framework, font, image, script, stylesheet, or CDN dependency is required. Folder selection uses the browser's directory-picker extension where supported. The page works as a local file for interface and persistence functions, but a normal HTTP or HTTPS origin is recommended for gateway calls and IndexedDB consistency.

## Feature coverage

### Single-face batch

- multiple donor images or folder selection;
- multiple targets or folder selection;
- full cross product;
- built-in engine selection;
- custom multipart endpoint and polling profile;
- concurrency for compatible custom endpoints;
- serialized built-in providers;
- retries, cancellation, retry failed, progress, and errors;
- real JPEG output and ZIP download.

### Multi-face batch

- up to 20 shared donor images;
- vertically expanding donor grid with labels below images;
- one or many target rows, up to 100;
- duplicate target recipes for different donor combinations;
- remote face analysis and visual overlays;
- manual face selection, sparse mappings, Auto Assign, Same Donor, and clear;
- donor auto-advance and row auto-advance;
- editing completed rows without an unwanted jump;
- stale-analysis refresh and normalized face reconciliation;
- sequential execution, continuation after failure, stable output ordering, and retry-failed rows.

### State and output

- IndexedDB draft restoration;
- local settings and optional local token storage;
- diagnostic export without image bytes or token values;
- individual downloads and a no-dependency STORE-method ZIP writer.

## Network boundary

The public HTML contains no personal signing key, gateway token, or private custom API credential. Live built-in engine requests are sent to the separately deployed gateway. User-selected images leave the browser when a live batch is submitted.

## Test

```bash
bash tests/gateway-smoke.sh
python tests/browser_smoke.py
```
