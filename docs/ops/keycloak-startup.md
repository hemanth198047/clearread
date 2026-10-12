# Keycloak local startup (ClearRead v1.0.6+)

Local Compose runs Keycloak **26.8** with `start-dev`, realm import, and a **persistent data volume** so warm boots skip re-import.

## Expected times

| Situation | Typical wait |
| --- | --- |
| **First boot** (empty volume, realm import) | **1–3 minutes** on Docker Desktop / constrained RAM |
| **Warm start** (volume already has realm) | Usually **under 30 seconds** |
| Start-script timeout | **240s** default (`CLEARREAD_KEYCLOAK_WAIT_SECONDS`) |

Scripts print progress every ~15s while waiting for:

`http://localhost:8081/realms/clearread/.well-known/openid-configuration`

(port follows `CLEARREAD_KEYCLOAK_PORT`).

## What we tuned (v1.0.6)

- `start-dev` + `--import-realm` + `--health-enabled=true` (import only when realm is missing)
- Volume `clearread_keycloak` → `/opt/keycloak/data`
- Realm JSON bind-mount: `infra/keycloak/realm-clearread.json` (relative to `infra/compose.yaml`)
- Healthcheck: **explicit `/bin/bash`** + `/dev/tcp` → management `/health/ready` (UBI has no curl; plain `sh` broke the probe)
- Management port published as host **`CLEARREAD_KEYCLOAK_MGMT_PORT` (default 9002)** → container 9000
- Soft heap `JAVA_OPTS_KC_HEAP=-Xms64m -Xmx768m` — **no hard `mem_limit`** (768m OOM-killed first-boot import on Docker Desktop)
- API `depends_on` Keycloak = **`service_started`** (scripts wait for OIDC; slow IdP does not block db/LocalStack)
- `start.sh` / `start.bat` / `start-deps.sh` / `start-deps.bat` fail clearly after the timeout with `docker logs --tail 80`

## Troubleshooting

### Still slow or timed out

```bat
docker logs clearread-keycloak-1
docker compose -p clearread -f infra/compose.yaml ps keycloak
scripts\doctor.bat
```

1. Give **Docker Desktop ≥ 4GB RAM** (8GB preferred if API + Keycloak + DB run together).
2. Recreate Keycloak:

```bat
docker compose -p clearread -f infra/compose.yaml up -d --force-recreate keycloak
```

3. Raise the wait: set `CLEARREAD_KEYCLOAK_WAIT_SECONDS=360` in `.env`.

### Realm JSON changed (new users / clients) but IdP looks stale

Import is **skipped** when the realm already exists in the volume. Wipe and re-import:

```bat
docker compose -p clearread -f infra/compose.yaml stop keycloak
docker volume rm clearread_clearread_keycloak
docker compose -p clearread -f infra/compose.yaml up -d keycloak
```

(Volume name is `{project}_clearread_keycloak`; project defaults to `clearread`.)

### Do not rebuild everything for IdP-only changes

```bat
docker compose -p clearread -f infra/compose.yaml up -d keycloak
```

No need to rebuild `api` / `web` images unless OIDC URLs or ports changed.

## Windows retry (full stack)

```bat
git pull
scripts\stop.bat
scripts\doctor.bat
scripts\start-deps.bat
scripts\start.bat
```

Or Git Bash / WSL (includes host API fallback if Compose networking is broken):

```bash
git pull
./scripts/stop.sh
./scripts/doctor.sh
./scripts/start-deps.sh
./scripts/start.sh
```

See also [compose-deps-fix.md](./compose-deps-fix.md).
