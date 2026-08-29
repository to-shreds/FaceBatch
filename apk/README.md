# Android snapshot

## Controlling release

FaceBatch **0.7.0** is the current Android release represented by this snapshot.

| Field | Value |
|---|---|
| Package | `com.jon.facebatch` |
| Version name | `0.7.0` |
| Version code | `13` |
| Minimum SDK | `29` |
| Target SDK | `34` |
| APK SHA-256 | `16659b7a3408e0d402e74092df23970c41382bb58370cc6ba75b6965635929bb` |
| Source ZIP SHA-256 | `efcd31d66fab87a579a211fd39d80e8faae19c0ea8c0ad89e328f41626d06a36` |
| Signing certificate SHA-256 | `999d07c46a3afe2bb1fa3e3b9039b6ea468dddf579c65684597725cfbe6f6f3a` |

See [`current/`](current/) for the release record and [`docs/`](docs/) for recovery guidance.

## Available source baseline

The locally available source attachment was version 0.4.0, not the current 0.7.0 source. A sanitized copy is preserved under [`archive/FaceBatch-0.4.0-source/`](archive/FaceBatch-0.4.0-source/) and as a ZIP. The build script requires externally supplied signing variables and will not generate a replacement key.

## Missing binary artifacts

The exact 0.7.0 APK and 0.7.0 raw source ZIP were not accessible as bytes in the active workspace. Their expected hashes are preserved so the authentic files can be recognized later. This repository does not mislabel a rebuilt 0.4.0 binary as 0.7.0.

## Signing key

No keystore or password is committed. The personal signing key must remain separately backed up. See [`docs/SIGNING_COMPATIBILITY.md`](docs/SIGNING_COMPATIBILITY.md).
