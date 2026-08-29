# Android source recovery procedure

1. Locate a candidate `FaceBatch-0.7.0-source.zip`.
2. Compute SHA-256 and require an exact match to `efcd31d66fab87a579a211fd39d80e8faae19c0ea8c0ad89e328f41626d06a36`.
3. Inspect archive integrity and confirm it excludes signing material and generated outputs.
4. Compare package, version, manifest, endpoints, model classes, editor-flow classes, and tests to the preserved release record.
5. Restore the personal keystore outside the repository and confirm certificate SHA-256 `999d07c46a3afe2bb1fa3e3b9039b6ea468dddf579c65684597725cfbe6f6f3a`.
6. Build the historical 0.7.0 source without changing it and compare the resulting structure to the release report. A deterministic APK byte match is not guaranteed, but package, code, resources, DEX features, signing identity, and behavior must match.
7. Create a new branch for future work. Increment version code to at least 14.
8. Preserve single-face and multi-face behavior unless a change is intentionally requested and tested.
9. Run local unit tests, editor-flow tests, archive integrity, zip alignment, APK signature verification, manifest checks, and DEX string/class checks.
10. Perform a physical-device update test and live end-to-end generation before release.

The sanitized 0.4.0 source can help recover shared engine code but must not be treated as the complete 0.7.0 source.
