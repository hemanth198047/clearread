# ClearRead Operator Runbook (Phase 6 freeze)

## Services

| Service | Default | Notes |
| --- | --- | --- |
| API | `:8080` | Health `/actuator/health`, Prometheus `/actuator/prometheus` (auth) |
| Web | `:5173` / `:80` in compose | SPA; OIDC PKCE |
| Postgres | `:5432` | Flyway on boot |
| Object store | `:9000` → LocalStack S3 | Prefix `tenants/{tenantId}/…` |
| Keycloak | `:8081` | Realm `clearread`, client `clearread-spa`, demo/demo |

## Capability honesty

UI and `/v1/capabilities` must only claim formats registered in `capabilities/registry.json`. Unsupported uploads are rejected. PDF export is a **constrained text PDF** converter (`cleardoc-to-pdf-constrained`) — generated artifact only, not a pixel-perfect print engine.

## IdP

- Production: replace demo Keycloak with enterprise IdP; rotate clients/secrets; disable `demo` user.
- Issuer + JWK set URIs via `CLEARREAD_OIDC_*`.

## AI kill switch

- Workspace `ai_enabled=false` (default) or set provider unavailable.
- Global: leave `CLEARREAD_AI_OLLAMA_ENABLED` / `CLEARREAD_AI_EXTERNAL_ENABLED` false.
- Core reader never calls AI.

## Backup / restore

```bash
./scripts/backup.sh [/path/out]
./scripts/restore.sh /path/to/backup-dir
```

Also snapshot object-store prefixes for the same window. Verify readiness after restore.

## Image pinning (production)

Compose pins **version tags**. For production, resolve digests and pin:

```bash
docker buildx imagetools inspect postgres:16-alpine --format '{{json .Manifest.Digest}}'
# then: image: postgres:16-alpine@sha256:…
```

Recommended pins for this release train: `postgres:16-alpine`, `localstack/localstack:4.3`, `quay.io/keycloak/keycloak:26.8.0`.

## Observability

- Liveness/readiness: Spring Boot probes
- Metrics: Micrometer → Prometheus scrape (authenticated)
- Logs: never enable `clearread.ai.log-document-content` in production

## Incident basics

1. Disable AI + sharing if content exfil suspected.
2. Rotate preview/share tokens (revoke APIs) and OIDC clients.
3. Requeue dead jobs from Admin after root-cause.
4. Restore from last known-good backup if integrity failure.
