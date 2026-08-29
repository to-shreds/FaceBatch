# FaceBatch development rules

These rules apply to future human and automated development in this repository.

1. Inspect the actual current source, release records, open work, and prior project decisions before editing. Do not restart the application or redesign unrelated behavior.
2. FaceBatch is a personal sideloaded Android utility. Preserve package `com.jon.facebatch`, working behavior, user data compatibility, and signing compatibility across releases.
3. The Android 0.7.0 release record is the controlling baseline. The 0.4.0 archive is historical recovery material, not the current implementation.
4. A normal Android release deliverable includes complete source, a compiled signed APK, a build report, checksums, and validation evidence. Code suggestions alone are not a release.
5. Never create a replacement signing key. Never commit a keystore or password. Verify the accepted certificate fingerprint before signing.
6. Increment Android version code above 13 for the next release. Do not change the package name.
7. Preserve single-face cross-product batching and multi-face recipe-row behavior unless a requested change expressly modifies them.
8. Multi-face generation must fail safely rather than map a donor to an uncertain refreshed face.
9. Keep the public HTML free of private signing material, gateway tokens, and custom API credentials. Deploy only `docs/` to Pages.
10. Run `python scripts/validate_snapshot.py`, `bash web/tests/gateway-smoke.sh`, `bash web/tests/custom-adapter-smoke.sh`, and `python web/tests/browser_smoke.py` after web or gateway changes.
11. Record material changes in `CHANGELOG.md`, update research or recovery notes when assumptions change, regenerate the manifest and root checksums, and create a tagged snapshot before risky work.
