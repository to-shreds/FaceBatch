# Archived Android source

`FaceBatch-0.4.0-source/` is a sanitized extraction of the available historical source. `FaceBatch-0.4.0-source-scrubbed.zip` is the corresponding portable archive.

Sanitization changes:

- removed local Android SDK paths and build outputs;
- excluded every keystore and password;
- changed `build-local.sh` to require external signing variables;
- added a fingerprint-only signing note;
- preserved production source and resources.

This source predates multi-face versions 0.5.0 through 0.7.0. It is included for recovery and regression reference, not as the current source tree.
