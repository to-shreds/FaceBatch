# Protocol and compatibility record

## AIFaceSwap multi-face flow

The Android line established the following sequence:

1. request a temporary upload URL from `/api/upload_file`;
2. upload normalized image bytes to the returned URL;
3. call `/api/extract_url_face` with the uploaded target path;
4. read face boxes from `data.pos` in `[left, top, right, bottom]` order;
5. upload each unique donor;
6. call `/api/generate_multi_face_v1` with `source_image`, sparse `face_image` mappings, `type_1`, and `type_2`;
7. use request type 2 and the same encrypted and signed request layer as the high-quality single route;
8. poll `/api/check_status` and download the result.

Unassigned target faces are omitted and remain unchanged.

## Mapping nonce

The nonce input starts with the target upload basename without its extension. Assignments are processed in target-face index order. Each adds:

`:<donor upload basename without extension>_<target face index>`

The final nonce is the MD5 digest of that complete string.

## Geometry reconciliation

A target can be reanalyzed before generation. Face mappings are preserved only when normalized geometry can match the previous boxes to the refreshed boxes safely. Ambiguous or missing matches fail closed rather than silently replacing the wrong face.

The web frontend retains this reconciliation model and passes the target's actual decoded dimensions to the matcher.

## Provider compatibility boundary

The gateway preserves the known request profiles, but third-party providers remain external dependencies. A service can change its schema, signing requirements, host, rate limit, or safety policy. Compatibility must be revalidated before release.

## Web compatibility

- Current Chromium, Firefox, and Safari support the core file, Blob, Canvas, Fetch, and IndexedDB features used by the page.
- Folder input is browser-dependent and uses `webkitdirectory` where available.
- Local `file:` use may give IndexedDB or network behavior a browser-specific origin. Serving the file over local HTTP is more predictable.
- Browser background execution is not an Android foreground service. The page saves draft state, but the browser can suspend a hidden tab.
