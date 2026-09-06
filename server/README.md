# PTT Relay Server — Northflank

Docker Server version `1.4.0-northflank` for the PTT Android and iOS clients.

## Northflank settings

- Build type: Dockerfile
- Dockerfile: `/Dockerfile`
- Build context: `/`
- Internal port: `8181`
- Public protocol: HTTP (Northflank provides HTTPS and WSS automatically)
- Instances: exactly `1`, because room and floor state are held in memory
- Health check: `GET /healthz`

Set these values as Northflank secrets or runtime variables. Never commit their real values.

```text
PTT_AUTH_REQUIRED=true
PTT_USER_TOKEN=<unique random value, at least 32 characters>
PTT_ADMIN_TOKEN=<different random value, at least 32 characters>
PTT_DASHBOARD_USER=<dashboard username>
PTT_DASHBOARD_PASSWORD=<unique random value, at least 16 characters>
PTT_DASHBOARD_ALLOW_REMOTE=true
PTT_TRUST_PROXY=true
PTT_VERSION=1.4.0-northflank
APNS_ENABLED=false
```

The public client endpoint will be `wss://<northflank-domain>` and the Dashboard will be `https://<northflank-domain>/`.
