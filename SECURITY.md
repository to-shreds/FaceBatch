# Security policy

## Private repository requirement

Keep the repository private. The browser page itself is designed for publication, but the repository also preserves provider adapter research and Android recovery material that should not be casually published.

## Public Pages boundary

Only `docs/` is intended for GitHub Pages deployment. It contains the standalone frontend and no FaceBatch personal signing key, signing password, gateway access token, or custom API credential.

The published HTML still sends user-selected images to the gateway and configured third-party provider when a live batch is run. That processing is not local-only. The interface states this boundary before network use.

## Secrets

Never commit:

- the FaceBatch `.jks` or any other keystore;
- signing store passwords or key passwords;
- gateway bearer tokens;
- private custom API credentials;
- `.env` deployment files;
- provider-issued temporary object-storage credentials;
- real user images or generated face-swap results.

Use environment variables or a platform secret store for gateway configuration. Rotate a gateway token immediately if it is exposed.

## Signing compatibility

The accepted FaceBatch signing certificate SHA-256 is:

`999d07c46a3afe2bb1fa3e3b9039b6ea468dddf579c65684597725cfbe6f6f3a`

A future APK signed by a different key will not install as an in-place update over existing FaceBatch installations. Treat the personal signing key as irreplaceable.

## External services

Built-in adapters target undocumented or third-party service flows that can change, rate-limit, reject requests, or stop operating. Do not assume availability. Do not use the tool to process images without the necessary rights and consent. Provider safety controls remain enabled in the built-in profiles.

## Reporting

This is a private personal project. Record security findings in a private repository issue without including credentials or private images.
