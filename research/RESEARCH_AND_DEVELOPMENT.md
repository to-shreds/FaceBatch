# FaceBatch research and development summary

## Objective

FaceBatch began as a personal Android batch utility for combining multiple donor faces with multiple target images. Development focused on preserving a compact workflow while reconstructing high-quality provider routes, managing unstable service identities, producing genuine JPEG output, and eventually supporting multiple independently mapped faces in each target.

## Android research history

### Configurable batch foundation

The first line established:

- multi-image and recursive folder selection;
- complete donor-by-target cross products;
- bounded concurrency and retries;
- continuation after individual failures;
- cancellation and retry failed;
- foreground processing while Android was minimized;
- saved output and remembered selections;
- configurable multipart fields, headers, authentication, response paths, and asynchronous polling.

### Provider reconstruction

The supplied modified Face Over APK was examined as behavioral and protocol evidence. Development reconstructed three families of routes:

- TaoAnhDep direct multipart generation;
- FJoy/Magicut registration, temporary object storage, generation, and polling;
- AIFaceSwap browser-session, temporary upload, encrypted and signed generation, polling, and result download.

The app added serialized provider workflows, session resets, account rotation, and explicit fallback behavior because unofficial services could reject or rate-limit repeated calls.

### High-quality AIFaceSwap path

Version 0.4.0 made AIFaceSwap the direct high-quality profile. It preserved the original compact cross-product batch and real JPEG conversion.

### Multi-face protocol

Version 0.5.0 added:

1. temporary upload of the target;
2. `extract_url_face` analysis returning target boxes;
3. temporary upload of unique donors;
4. sparse target-index to donor-URL mappings;
5. `generate_multi_face_v1` with request type 2;
6. a mapping-derived nonce;
7. polling and WebP-to-JPEG conversion.

A live smoke test in the prior build work produced two detected faces, mapped one donor to both, generated a valid result, and confirmed decoded-display orientation handling.

### Multi-row batch editor

Version 0.6.0 added up to 100 ordered target recipes. One original could be duplicated into many versions with different face pairs, or many originals could be configured line by line. The batch remained sequential and stable, continued after row failures, retained failed rows, refreshed stale analysis, and reconciled normalized geometry before generation.

Version 0.7.0 then removed practical editor friction:

- all donors remain visible;
- labels sit below face images rather than covering expressions;
- donor selection moves to the next unassigned face;
- row completion moves to the next unfinished target;
- completed rows remain freely editable;
- shortcuts follow the same navigation rules.

## Browser conversion

The browser conversion retained the complete workflow model but separated the product into two security zones:

### Public standalone frontend

The single HTML file owns all interface and local logic, including file selection, target rows, assignments, progression, persistence, JPEG conversion, ZIP output, settings, and diagnostics. It contains no personal Android signing material or gateway access token.

### Private network gateway

The Node gateway owns provider-specific request adapters. This is necessary because a static browser page cannot safely protect secrets or rely on unrestricted cross-origin access. A configurable token and exact origin list protect the gateway itself.

## Preservation decisions

- Android 0.7.0 remains the controlling state even though only 0.4.0 source bytes were locally available.
- Historical artifact hashes are treated as immutable identities.
- The personal signing key is excluded from every repository artifact.
- The 0.4.0 source is retained only as a sanitized recovery baseline.
- The web application does not claim that browser background execution is identical to an Android foreground service. It preserves batch state and can continue while the tab remains active, but browser lifecycle control belongs to the browser.
- Mock tests are clearly separated from live-provider validation.

## Future development priorities

1. Restore and verify the authentic 0.7.0 Android source.
2. Deploy the private web gateway to a controlled HTTPS host.
3. Add server-side encrypted temporary-result storage only if direct response streaming becomes insufficient.
4. Add an optional downloadable job manifest for large web batches.
5. Re-run live provider validation before any new release because unofficial APIs can change.
6. Keep the package name, signing certificate, and feature behavior stable across Android updates.
