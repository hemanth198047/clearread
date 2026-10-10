# Phase 6 Exit Package — Production hardening

**Status:** Complete  
**Branch:** `cursor/phase-5-6-hardening-f2a6`

## 1. What works

- Security headers (`X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, `Permissions-Policy`, COOP/CORP, HSTS when HTTPS)
- Coarse per-IP API rate limit (`clearread.limits.api-requests-per-minute`, default 600)
- Actuator metrics + Prometheus registry (`/actuator/prometheus`, authenticated)
- Backup / restore scripts (`scripts/backup.sh`, `scripts/restore.sh`) + operator runbook
- Compose image pins documented; Keycloak/Postgres/LocalStack versions fixed in compose
- Threat-model delta for AI enrichment + prior SSRF/tenancy controls
- Operator / IdP / capability honesty freeze notes
- Go-live decision record with residual risks
- Prior phases (reader themes, SSRF, org workflows, optional AI) intact

## 2. Limitations

- Rate limit is in-process (per API instance); edge WAF still recommended
- Image digests: compose uses version tags; digest pinning procedure documented for production registries
- Full pen-test / load soak not executed in this environment (budgets + check suite documented)
- DR drill is scripted locally; production DR depends on operator-owned backups

## 3. Checks run

| Check | Result |
| --- | --- |
| Backend unit + integration (incl. Phase 5 AI + Phase 6 headers/rate-limit) | see CI / local `./gradlew test` |
| Frontend `npm test` / `tsc` | see batch run |

## 4. Local run

```bash
./scripts/start.sh
./scripts/backup.sh
# ./scripts/restore.sh .clearread-run/backups/<stamp>
./scripts/check.sh
./scripts/stop.sh
```

## 5. Demo media

- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-6/health-metrics.png`
- `/cursor/stores/bc-9ed4574f-12f8-4b1b-af4c-f90a53a80468/media/phase-6/operator-runbook.png`

## 6. Go-live

See [`go-live-decision.md`](./go-live-decision.md).
