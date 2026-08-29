# FaceBatch Web Gateway

The gateway is a small Node.js 22+ HTTP service with no package dependencies. It gives the static FaceBatch page a controlled same-purpose API while keeping provider adapters and restricted request headers out of the public Pages artifact.

## Routes

- `GET /v1/health`
- `POST /v1/session/start`
- `POST /v1/session/end`
- `POST /v1/analyze`
- `POST /v1/single`
- `POST /v1/multi`

Image requests use `multipart/form-data`. The gateway returns image bytes for successful generation and JSON for health, sessions, and analysis.

## Environment

| Variable | Purpose | Default |
|---|---|---|
| `HOST` | bind address | `127.0.0.1` |
| `PORT` | port | `8787` |
| `FACEBATCH_GATEWAY_TOKEN` | required bearer or `X-FaceBatch-Token` value | empty, which is rejected outside mock development |
| `FACEBATCH_ALLOWED_ORIGINS` | comma-separated exact origins | none |
| `FACEBATCH_MOCK` | use local mock responses when `1` | `0` |
| `FACEBATCH_MAX_BODY_BYTES` | maximum request size | 35 MiB |

## Local mock mode

```bash
FACEBATCH_MOCK=1 \
FACEBATCH_GATEWAY_TOKEN=dev-token \
FACEBATCH_ALLOWED_ORIGINS=http://127.0.0.1:8765 \
node server.mjs
```

## Production requirements

- HTTPS only;
- a long random access token stored in the deployment platform secret manager;
- `FACEBATCH_ALLOWED_ORIGINS` restricted to the exact Pages origin and any intentional local development origins;
- process supervision and logs that do not retain image bodies or credentials;
- reasonable platform request-size and execution-time limits;
- no public unauthenticated gateway.

The current server is stateless except for in-memory provider sessions. Restarting the process starts fresh provider identities. Multi-instance deployment can produce different session behavior unless requests are sticky or session state is externalized.

## Mock behavior

Mock mode returns deterministic local analysis boxes and copies the target image as the generated result. It is for interface and transport tests only and does not perform a face swap.
