# Admin guide (ClearRead v1.0)

## Identity

- Local Compose Keycloak realm `clearread` seeds **admin** / **admin**, **user** / **user**, and legacy **demo** / **demo**. Self-registration is on for local DX.
- Production: replace demo Keycloak with your enterprise IdP; rotate clients; disable seeded users and IdP self-registration; set `CLEARREAD_SHOW_DEMO_ACCOUNTS=false`.
- Configure issuer + JWK via `CLEARREAD_OIDC_ISSUER` and `CLEARREAD_OIDC_JWK_SET_URI`.
- SPA client: PKCE public client (local: `clearread-spa`).

## Inviting users

1. Sign in as a workspace **admin**.
2. Admin → **Invite user** → email + role → **Create invite link**.
3. Copy the invite URL (ClearRead does not send email). Share it with the invitee.
4. Invitee opens `/invite/{token}`, creates an account (or signs in) with that email; first login accepts the invite and joins the workspace with the invited role.

Enterprise alternative: provision users in your org IdP / SSO and map them into the workspace (or use IdP invite flows). ClearRead records pending invites in Postgres (`workspace_invites`) for auditability.

## Branding

Environment overrides (see `application.yml`):

- `CLEARREAD_PRODUCT_NAME`
- `CLEARREAD_PRIMARY_COLOR` / `CLEARREAD_SECONDARY_COLOR`
- `CLEARREAD_LOGO_URL`
- `CLEARREAD_SHOW_DEMO_ACCOUNTS` — landing-page demo credentials (default `true` locally)

`GET /v1/branding/default` exposes the active branding to the SPA (includes `showDemoAccounts`).

## Workspace / AI policy

- Core reader never requires AI.
- Admin UI + `PUT /v1/tenants/{id}/settings` control `aiEnabled`, local-only, external allow, provider id, quotas.
- **AI provider connection (v1.2.9+):** Admin → AI provider connection + `GET/PUT /v1/tenants/{id}/ai-connection` set Ollama base URL/model and external URL/model/API key. Keys are stored in Postgres and never echoed in full (`apiKeyHint` only). Env vars (`CLEARREAD_AI_*`) remain bootstrap/fallback.
- **External provider presets (v1.2.10+):** Dropdown for OpenAI / Anthropic / Azure / Groq / OpenRouter / Custom fills endpoint + model; paste API key. Main app shows an **AI enabled** chip in the header (and Settings / Home) when workspace policy has enrichment on.
- **External key → available (v1.2.11+):** Saving an external connection auto-aligns policy (provider=external, allow-external on, local-only off, AI enabled). Use **Test connection** (`POST /v1/tenants/{id}/ai-connection/test`) for a clear success/failure message. If policy still blocks external, enrichment status returns an actionable `availabilityMessage`.
- Compose default Ollama URL from the API container: `http://host.docker.internal:11434` (see `infra/compose.yaml` + `.env.example`).
- Global kill switches / env bootstrap: `CLEARREAD_AI_OLLAMA_ENABLED`, `CLEARREAD_AI_EXTERNAL_ENABLED` (default false). Prefer enabling via Admin connection settings for local demos.
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
