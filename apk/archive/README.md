# Archived Android source

`FaceBatch-0.4.0-source/` is the sanitized, reviewable copy of the available historical Android source preserved in this repository. The extracted tree is the canonical GitHub recovery artifact.

Sanitization changes:

- removed local Android SDK paths and build outputs;
- excluded every keystore and password;
- changed `build-local.sh` to require external signing variables;
- added a fingerprint-only signing note;
- preserved production source and resources.

This source predates multi-face versions 0.5.0 through 0.7.0. It is included for recovery and regression reference, not as the current source tree.

The complete offline snapshot package created on August 29, 2026 also contains a redundant portable scrubbed ZIP of this tree. The GitHub repository keeps the extracted source directly so individual revisions remain inspectable and diffable.
