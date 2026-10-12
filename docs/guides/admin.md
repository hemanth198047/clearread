# Admin guide (ClearRead v1.0)

## Identity

- Production: replace demo Keycloak with your enterprise IdP; rotate clients; disable `demo`.
- Configure issuer + JWK via `CLEARREAD_OIDC_ISSUER` and `CLEARREAD_OIDC_JWK_SET_URI`.
- SPA client: PKCE public client (local: `clearread-spa`).

## Branding

Environment overrides (see `application.yml`):

- `CLEARREAD_PRODUCT_NAME`
- `CLEARREAD_PRIMARY_COLOR` / `CLEARREAD_SECONDARY_COLOR`
- `CLEARREAD_LOGO_URL`

`GET /v1/branding/default` exposes the active branding to the SPA.

## Workspace / AI policy

- Core reader never requires AI.
- Admin UI + `PUT /v1/tenants/{id}/settings` control `aiEnabled`, local-only, external allow, quotas.
- Global kill switches: `CLEARREAD_AI_OLLAMA_ENABLED`, `CLEARREAD_AI_EXTERNAL_ENABLED` (default false).
- Never enable `clearread.ai.log-document-content` in production.

## Jobs & recovery

- DB-backed job leasing with retries. Failed documents expose diagnostics; operators can reprocess.
- Dead / stuck jobs: investigate logs, then requeue from Admin after root cause.

## Sharing & previews

- Share links enforce expiry, revoke, and permission flags server-side.
- Preview tokens are TTL’d and view-capped.

## Observability

- Liveness/readiness: Spring Boot probes (`/actuator/health`).
- Metrics: Prometheus at `/actuator/prometheus` (authenticated).
- Application logs: never log document bodies in production.

## Capability honesty

UI and API claims must match `GET /v1/capabilities`. Do not advertise AsciiDoc/RST ingest or live Mermaid rendering — they are final v1.0 non-goals / safe partials. See [format-support.md](../format-support.md).

Full operator detail: [phase-6/operator-runbook.md](../phase-6/operator-runbook.md).
