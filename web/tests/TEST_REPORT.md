# FaceBatch Web 1.0 validation report

**Test date:** 2026-08-29  
**Application:** `1.0.0-snapshot-2026-08-29`  
**Result:** PASS for the local and synthetic scope described below

## Static validation

- HTML document structure present.
- The single inline application script parses successfully in Node.
- Gateway source passes `node --check`.
- Canonical `web/FaceBatch.html`, `web/index.html`, and `docs/index.html` are byte-identical.
- The application has no remote script, stylesheet, font, or image dependency.
- No signing key, keystore password, gateway token, or personal credential is present in the Pages artifact.
- No Unicode em dash is present in text files.

## Gateway mock integration

Tested and passed:

- health response;
- exact allowed-origin CORS response;
- 403 for a disallowed origin;
- 401 for a wrong token;
- session start and cleanup;
- target analysis response;
- single image response;
- multi image response.

## Browser workflow

Executed in Chromium through Playwright and passed:

- gateway settings and connection check;
- single batch with two donors and one target, producing two results;
- generated result conversion to JPEG;
- download-all ZIP generation and ZIP integrity;
- IndexedDB restoration after reload;
- two analyzed multi-face rows;
- donor assignment auto-advance through faces and then to the next unfinished row;
- editing a completed row without an unintended row jump;
- Auto Assign and Same Donor completion navigation;
- two-row sequential multi-face generation;
- no uncaught page errors during the tested flows.

## Responsive layout

At 390 pixels wide, 20 donor images labeled A through T remained in a wrapping, vertically expanding grid without horizontal overflow. Desktop multi-face and result screens were also captured.

## Custom API adapter

A local HTTPS synthetic provider exercised:

- immediate binary image response;
- configured JSON result path;
- fallback JSON path detection;
- plain-text result URL;
- asynchronous polling;
- custom multipart fields;
- custom request headers;
- custom origin and User-Agent handling;
- custom profile selection from the web interface.

All passed.

## External-service limitation

No live third-party generation was performed in this conversion environment. Mock and synthetic fixtures validate FaceBatch's browser and gateway behavior, not the present availability or policy of any third-party endpoint.

## Android limitation

The exact Android 0.7.0 source and APK bytes were not available locally, so no replacement APK was compiled. Historical Android validation is preserved separately under `apk/current/`.
