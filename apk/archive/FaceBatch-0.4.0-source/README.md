# FaceBatch 0.4.0

FaceBatch is a personal, sideloaded Android utility that creates every donor-face x target-image combination and saves completed results under Downloads.

## 0.4.0 high-quality engine

- Added the AIFaceSwap high-quality engine reconstructed from Face Over's route 0 workflow.
- AIFaceSwap HQ is now the default engine and does not require a user API key.
- Face Over Auto now follows route 0 to AIFaceSwap rather than silently substituting FJoy.
- Face Over route 2 still uses FJoy / Magicut, and route 3 still uses TaoAnhDep.
- If Face Over routing is unavailable, Auto uses AIFaceSwap HQ rather than FJoy.
- If Tao is rate-limited or returns a server error in Auto mode, the fallback is AIFaceSwap HQ.
- FJoy remains available as a separately labeled lower-quality fallback.
- The compact interface, automatic WebP-to-JPEG conversion, and FJoy three-swap session rotation remain intact.

## Live-test note

The APK and protocol were reconstructed and compiled from the supplied Face Over APK, but the isolated build environment cannot complete a live generation request. Start with one donor and one target. If the provider has changed a signing or response detail, FaceBatch reports the precise AIFaceSwap stage that failed.

## Fixes in 0.3.1

This update corrects the FJoy / Magicut request profile after a live server response identified a mismatch in the reconstructed client.

- Corrected the package identifier sent to FJoy from `com.faceswap.faceover` to the exact identifier used by the supplied Face Over APK: `com.video.reface.app.faceplay.deepface.photo`.
- Corrected the FJoy S3 object directory to the fixed path used by Face Over: `changefacepic/TX014`.
- Corrected FJoy object naming to match Face Over's SHA-256 timestamp-plus-user-id format.
- Serialized Face Over Auto, FJoy, and Tao workflows so their temporary identities, upload credentials, and server pacing cannot overlap across batch workers.
- Added stage-specific FJoy errors for registration, identity, upload, submission, and polling failures.
- Removed the separate key-based developer API from the primary engine switch. Existing installs that had selected that service are automatically moved back to Face Over Auto.
- Kept the key-based developer API under Advanced, labeled clearly as an external service that requires its own API key.

## Recommended setting

Use **Face Over Auto** first. It follows Face Over's routing service and needs no user API key. **FJoy / Magicut** is available as a direct no-key fallback. **TaoAnhDep direct** remains available for testing, but that endpoint may rate-limit direct callers.

## Important limitation

The build environment can compile, sign, and inspect the APK but cannot make live requests to the external face-swap servers. The corrected FJoy package and upload details were verified against the supplied APK's resources and bytecode, but the final live request must still be tested on an Android device.


## 0.3.3 batch-session reset

FaceBatch now clears only its internal FJoy identity at the beginning of every new batch. This mimics the relevant effect of clearing app data or reinstalling, while preserving normal settings, selected images, and downloaded results. Session preference writes are synchronous so a stale FJoy user cannot leak into the next batch.


## Per-batch FJoy reset

Version 0.3.3 treats FJoy identity data as batch-scoped. It synchronously clears only the private FJoy identity and user-id preferences before each Face Over Auto or FJoy batch, reuses the newly created identity within that batch, and clears it again when the batch ends. Normal FaceBatch settings, selected images, and output files are not erased.


## FJoy session rotation in 0.3.4

FaceBatch resets only its private reconstructed FJoy identity after every four successfully completed FJoy swaps. The fifth combination creates a fresh backend identity automatically. Ordinary settings, selections, and downloaded results are preserved.


## 0.3.4 four-swap rotation

For Face Over Auto and FJoy / Magicut, FaceBatch now counts successful swaps in the current private FJoy session. After four successful results, it clears only that reconstructed FJoy identity before starting the fifth combination. The cycle repeats automatically for larger batches. FaceBatch settings, selected images, and downloaded results are not cleared.

## 0.3.6 compact screen and JPEG output

- WebP results are decoded and saved automatically as JPEG files at 95% quality. Transparent pixels, if any, are flattened onto white.
- The large headline, introduction, privacy block, and footer were removed from the main screen.
- Selection cards, buttons, thumbnails, spacing, and batch summary were condensed so the full normal workflow fits on screen without scrolling on typical modern phones.


## FJoy session rotation in 0.3.6

FaceBatch now rotates to a fully fresh reconstructed FJoy installation identity after every three completed FJoy swaps. The reset clears all legacy FJoy preference namespaces, any process-wide HTTP cookies, and FJoy temporary cache files, then pauses seven seconds before the next group so the newly registered points-system user can propagate across the provider's services.
