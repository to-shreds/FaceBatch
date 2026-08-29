# Web validation

## `gateway-smoke.sh`

Starts the gateway in mock mode on an available local port and checks:

- health response;
- allowed-origin CORS header;
- rejected origin;
- rejected token;
- session start and end;
- target analysis;
- single result bytes;
- multi result bytes.

## `browser_smoke.py`

Starts a local static server and mock gateway, creates synthetic image fixtures at runtime, and exercises Chromium through Playwright:

- gateway configuration and health;
- two-donor by one-target single batch;
- two multi-face target rows;
- face analysis and automatic donor/row advance;
- multi-face execution;
- results and ZIP creation;
- IndexedDB draft restoration;
- responsive donor layout.

It contacts no external provider.

## Evidence

`TEST_REPORT.md` is the canonical record of the completed validation. The original conversion workspace also retained screenshot and reusable fixture binaries. Those convenience artifacts are not required to run the tests and are not mirrored in the private GitHub snapshot. `browser_smoke.py` generates its synthetic image inputs at runtime.

## `custom-adapter-smoke.sh`

Starts a temporary self-signed HTTPS provider and checks immediate image, configured JSON path, fallback JSON path, plain URL, asynchronous polling, custom fields, and custom headers. It sets `NODE_TLS_REJECT_UNAUTHORIZED=0` only for the isolated local test process. Never use that setting in production.
