# Android signing compatibility

FaceBatch update compatibility depends on preserving both the package name and signing certificate.

## Required identity

- Package: `com.jon.facebatch`
- Accepted signing certificate SHA-256: `999d07c46a3afe2bb1fa3e3b9039b6ea468dddf579c65684597725cfbe6f6f3a`
- Last released version code: `13`

## Rules

1. Never generate a new key as a convenience fallback.
2. Keep the personal keystore and passwords outside Git and outside the repository ZIP.
3. Verify the certificate fingerprint before signing.
4. Use version code 14 or higher for the next update.
5. Verify APK Signature Schemes v1, v2, and v3 unless a later Android design decision deliberately changes compatibility.
6. Install the new APK over an existing FaceBatch installation on a physical device before calling it update-compatible.

## Sanitized build variables

The archived build script reads:

- `FACEBATCH_KEYSTORE`
- `FACEBATCH_STOREPASS`
- `FACEBATCH_KEY_ALIAS`
- `FACEBATCH_KEYPASS`

Do not put literal values in the script or repository.
