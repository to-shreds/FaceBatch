# Source and evidence inventory

## Local artifacts used

| Artifact | Role | SHA-256 | Repository treatment |
|---|---|---|---|
| FaceBatch 0.4.0 source attachment | Available Android source baseline | `5651158224cec2d4df084bfca654139dc51b637d3003414eaf5fb9f191aa3eca` | Original excluded; sanitized source and ZIP included |
| FaceBatch personal signing-key archive | Android update authority | `c22b7387babdca65735c05cd4511ccae4afa519462082610bc39cc1d4e6eb68b` | Excluded from Git and all distribution ZIPs |
| Modified Face Over 8.0 reference APK | Protocol research reference | `506a550ffbbc1e5f9fe51565f59a7918f748605dc04a327b1006076692b9fccf` | Excluded from repository |

The signing archive's hash is recorded only so the external backup can be recognized. No password or private key content is recorded.

## Preserved release evidence

- FaceBatch 0.4.0 build report and hash
- FaceBatch 0.5.0 protocol and live-smoke report
- FaceBatch 0.6.0 batch-model and persistence report
- FaceBatch 0.7.0 editor-flow report and artifact hashes
- prior project conversations documenting successful phone use and requested workflow changes

## Trust ordering

1. Exact artifact bytes plus matching cryptographic hash
2. Exact source plus compiled validation evidence
3. Original release report and checksum record
4. Project conversation and user-confirmed behavior
5. Reconstructed documentation

The 0.7.0 source and APK currently sit at level 3 because their original bytes were not available in the active workspace. The snapshot does not promote reconstructed files to level 1.
