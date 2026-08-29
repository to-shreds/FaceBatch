# Snapshot status

**Snapshot date:** 2026-08-29  
**Repository posture:** intended to remain private  
**Pages artifact:** public static frontend only  
**Android controlling release:** 0.7.0  
**Web snapshot release:** 1.0.0-snapshot-2026-08-29

## Completed

1. Reconstructed the latest known FaceBatch state from project history, release reports, source, signing metadata, and the supplied reference APK.
2. Preserved Android 0.7.0 as the controlling release rather than incorrectly treating the older 0.4.0 attachment as current.
3. Built a responsive, standalone HTML frontend that preserves the current single-face and multi-face workflows.
4. Implemented a private Node gateway for built-in engines and custom API profiles.
5. Preserved Android signing compatibility information without committing private signing material.
6. Added mock, browser, custom-profile, persistence, ZIP, responsive-layout, and workflow tests.
7. Prepared a GitHub Pages artifact and deployment workflow.
8. Added a one-command GitHub publishing helper for environments with an authenticated GitHub CLI.
9. Added a complete repository manifest, checksums, recovery documentation, and a Git bundle workflow.

## Deliberate limitations

- The actual 0.7.0 APK and 0.7.0 source ZIP were not present as local bytes in this workspace. Their release identities and SHA-256 values are preserved. They were not replaced with files from an older version.
- The private Android signing key is excluded. The separately supplied signing-key archive remains the authority for future signed releases.
- GitHub repository creation, visibility changes, and Pages enablement require repository-administration actions not exposed by the installed GitHub connector. `scripts/publish-github.sh` performs those steps from an authenticated local GitHub CLI.
- GitHub Pages serves static files. Live built-in-provider swaps require the separate HTTPS gateway because the browser cannot safely store protocol secrets or reliably bypass cross-origin rules.
- No live third-party generation was performed during this web conversion. All frontend and gateway behavior was exercised against local mock and synthetic provider fixtures.

## Recovery priority

For Android recovery, obtain the exact 0.7.0 source ZIP whose expected SHA-256 is recorded in `apk/current/SHA256SUMS.txt`. Restore the personal signing key outside the repository, verify its certificate fingerprint, then build version code 14 or greater. Never create a replacement key.
