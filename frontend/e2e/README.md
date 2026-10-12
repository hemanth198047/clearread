# ClearRead Playwright smoke (v1.0)

Browser smokes for: **paste→read**, **upload→read**, **share create/revoke**, **export download**, **theme switch**.

## Prerequisites

1. Full stack healthy: `./scripts/start.sh` then `./scripts/check.sh`
2. Node 22+ in `frontend/`
3. Chromium for Playwright:

```bash
cd frontend
npm install
npm run test:e2e:install
```

## Run

```bash
cd frontend
npm run test:e2e
```

Optional env:

| Variable | Default | Purpose |
| --- | --- | --- |
| `CLEARREAD_WEB_URL` | `http://localhost:5173` | SPA base URL |
| `CLEARREAD_API_URL` | `http://localhost:8080` | API for readiness + token |
| `CLEARREAD_OIDC_TOKEN_URL` | Keycloak token endpoint | Password grant for `demo`/`demo` |
| `CLEARREAD_E2E_USER` / `CLEARREAD_E2E_PASSWORD` | `demo` / `demo` | Test user |
| `CLEARREAD_E2E_FORCE` | unset | Set to `1` to fail instead of skip when stack is down |

## Docker / agent environments

If Docker or the API is unavailable, tests **skip** (exit 0) unless `CLEARREAD_E2E_FORCE=1`. CI with Compose should run the suite against a live stack.
