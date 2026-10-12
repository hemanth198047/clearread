# Troubleshooting (ClearRead v1.0)

## Compose API never healthy (Docker bridge)

Published ports work from the host, but `api` cannot reach `db` / Keycloak / object store on the Compose network.

1. `docker compose -p clearread -f infra/compose.yaml logs --tail=80 api`
2. Prefer `./scripts/start.sh` — waits for health, then **falls back** to host `bootRun` + Vite.
3. Windows `scripts\start.bat` has **no** host fallback — use `bash scripts/start.sh`.
4. Smoke: `./scripts/check.sh`.

## Image build exit 127 (`./gradlew`)

Wrapper not executable or CRLF endings. See [ops/compose-build-fix.md](../ops/compose-build-fix.md). `.gitattributes` forces LF on `gradlew`.

## Unsupported format (415)

AsciiDoc/RST and other unsupported registry entries are **detected then rejected**. Convert to Markdown or use an ingestible format. See [format-support.md](../format-support.md) and `GET /v1/capabilities`.

## Document stuck / failed

1. Open reader diagnostics / processing notes.
2. **Reprocess** from the recovery panel.
3. Download original (checksummed) if needed.
4. Check API logs and Admin job state.

## Share / preview failures

- Expired, revoked, or permission flags are enforced server-side.
- Auth-required shares need a signed-in user.
- Create a fresh link after revoke.

## Object store after DB restore

Metadata without objects yields broken originals. Re-sync `tenants/*` for the same backup window ([backup-restore](./backup-restore.md)).

## Playwright E2E skipped

Suite needs a running stack (`./scripts/start.sh`). Without Docker/API, tests skip. See `frontend/e2e/README.md`.

## More

[Operator runbook](../phase-6/operator-runbook.md) · [scripts matrix](../ops/scripts.md)
