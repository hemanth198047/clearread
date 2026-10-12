#!/usr/bin/env bash
# ClearRead Compose dependency doctor — diagnose why db / LocalStack / Keycloak fail to start.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
COMPOSE_FILE="$ROOT/infra/compose.yaml"
PROJECT="clearread"

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

CLEARREAD_API_PORT="${CLEARREAD_API_PORT:-8080}"
CLEARREAD_WEB_PORT="${CLEARREAD_WEB_PORT:-5173}"
CLEARREAD_KEYCLOAK_PORT="${CLEARREAD_KEYCLOAK_PORT:-8081}"
CLEARREAD_KEYCLOAK_MGMT_PORT="${CLEARREAD_KEYCLOAK_MGMT_PORT:-9002}"
CLEARREAD_DB_PORT="${CLEARREAD_DB_PORT:-5433}"
CLEARREAD_S3_PORT="${CLEARREAD_S3_PORT:-9000}"

COMPOSE=(docker compose -p "$PROJECT" -f "$COMPOSE_FILE")
if [[ -f "$ROOT/.env" ]]; then
  COMPOSE+=(--env-file "$ROOT/.env")
fi

echo "=== ClearRead doctor ==="
echo "Repo: $ROOT"
echo "Compose file: $COMPOSE_FILE"
echo "Project: $PROJECT"
echo

next_action=""
created_stuck=0

print_created_stuck_recovery() {
  cat <<'EOF'

!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
DIAGNOSIS: Containers stuck in Created — Docker Desktop / WSL is wedged
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
  STATUS=Created with empty logs means `docker start` never completed.
  This is NOT Keycloak slowness, NOT a port conflict, NOT a ClearRead
  healthcheck bug. App Compose cannot work around a wedged Docker engine.

  If `docker start clearread-db-1` itself hangs, ONLY this recovery works:

  1. Quit Docker Desktop completely (tray icon → Quit)
  2. In an elevated / normal PowerShell or cmd:
       wsl --shutdown
  3. Start Docker Desktop and wait until the whale icon is green / "Running"
  4. Then:
       docker start clearread-db-1
     (use a short patience window — if THIS hangs, repeat steps 1–3)
  5. Re-run:  ./scripts/doctor.sh   then   ./scripts/start-deps.sh

  Docs: docs/ops/compose-deps-fix.md#containers-stuck-in-created
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

EOF
}

if ! command -v docker >/dev/null 2>&1; then
  echo "FAIL  Docker CLI not found on PATH"
  echo
  echo "Next action: Install Docker Desktop, ensure it is running, then re-run ./scripts/doctor.sh"
  exit 1
fi
echo "OK    Docker CLI present: $(docker --version 2>/dev/null | head -1)"

if ! docker info >/dev/null 2>&1; then
  echo "FAIL  Docker daemon not reachable (is Docker Desktop running?)"
  echo
  echo "Next action: Start Docker Desktop, wait until it is healthy, then re-run ./scripts/doctor.sh"
  exit 1
fi
echo "OK    Docker daemon reachable"

if docker compose version >/dev/null 2>&1; then
  echo "OK    $(docker compose version 2>/dev/null | head -1)"
else
  echo "FAIL  Docker Compose V2 plugin missing (need: docker compose …)"
  echo
  echo "Next action: Update Docker Desktop / install Compose V2, then retry"
  exit 1
fi

echo
echo "--- Host ports (defaults / .env) ---"
printf "  API=%s  Web=%s  Keycloak=%s  KC-mgmt=%s  Postgres=%s  S3/LocalStack=%s\n" \
  "$CLEARREAD_API_PORT" "$CLEARREAD_WEB_PORT" "$CLEARREAD_KEYCLOAK_PORT" \
  "$CLEARREAD_KEYCLOAK_MGMT_PORT" "$CLEARREAD_DB_PORT" "$CLEARREAD_S3_PORT"

port_listening() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn 2>/dev/null | awk -v p=":$port" '$4 ~ p"$" { found=1 } END { exit !found }' && return 0
    return 1
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && return 0
    return 1
  fi
  (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1
}

port_owned_by_clearread() {
  local port="$1"
  docker ps --format '{{.Names}} {{.Ports}}' 2>/dev/null \
    | grep -E "^${PROJECT}-" \
    | grep -E "(^|[^0-9])${port}->" >/dev/null 2>&1
}

check_port() {
  local port="$1" name="$2" env_var="$3" suggest="$4"
  if ! port_listening "$port"; then
    echo "OK    Port $port ($name) is free"
    return 0
  fi
  if port_owned_by_clearread "$port"; then
    echo "OK    Port $port ($name) owned by ClearRead Compose"
    return 0
  fi
  echo "WARN  Port $port ($name) is in use by another process"
  echo "      Set ${env_var}=${suggest} in .env  (see .env.example)"
  if [[ -z "$next_action" ]]; then
    next_action="Free port $port or set ${env_var}=${suggest} in .env, then ./scripts/start-deps.sh"
  fi
}

check_port "$CLEARREAD_DB_PORT" "Postgres" "CLEARREAD_DB_PORT" 15432
check_port "$CLEARREAD_S3_PORT" "S3/LocalStack" "CLEARREAD_S3_PORT" 19000
check_port "$CLEARREAD_KEYCLOAK_PORT" "Keycloak" "CLEARREAD_KEYCLOAK_PORT" 18081
check_port "$CLEARREAD_KEYCLOAK_MGMT_PORT" "Keycloak mgmt" "CLEARREAD_KEYCLOAK_MGMT_PORT" 19002
check_port "$CLEARREAD_API_PORT" "API" "CLEARREAD_API_PORT" 18080
check_port "$CLEARREAD_WEB_PORT" "Web" "CLEARREAD_WEB_PORT" 5174

echo
echo "--- docker compose ps ---"
if ! "${COMPOSE[@]}" ps -a 2>&1; then
  echo "(compose ps failed — project may not exist yet)"
fi

echo
echo "--- Containers stuck in Created? ---"
for svc in db minio keycloak; do
  local_line="$("${COMPOSE[@]}" ps -a --format '{{.Service}}|{{.State}}|{{.Status}}|{{.Name}}' 2>/dev/null | awk -F'|' -v s="$svc" '$1==s {print; exit}')"
  if [[ -z "$local_line" ]]; then
    echo "INFO  $svc — no container yet"
    continue
  fi
  state="$(echo "$local_line" | cut -d'|' -f2)"
  status="$(echo "$local_line" | cut -d'|' -f3)"
  cname="$(echo "$local_line" | cut -d'|' -f4)"
  if echo "$state $status" | grep -Eiq '(^|[[:space:]])created([[:space:]]|$)'; then
    log_bytes="$("${COMPOSE[@]}" logs --tail=20 "$svc" 2>/dev/null | wc -c | tr -d ' ')"
    echo "FAIL  $svc STATUS=Created (never running)  container=${cname:-?}  logs≈${log_bytes} bytes"
    if [[ "${log_bytes:-0}" -eq 0 ]] || [[ "${log_bytes:-0}" -lt 8 ]]; then
      echo "      Empty logs confirm the process never started — Docker engine start is stuck."
    fi
    if [[ -n "${cname:-}" ]]; then
      echo "--- docker inspect State ($cname) ---"
      docker inspect --format '{{json .State}}' "$cname" 2>/dev/null || true
      echo
    fi
    created_stuck=1
  else
    echo "OK    $svc state=${state:-?} status=${status:-?}"
  fi
done

if [[ "$created_stuck" -eq 1 ]]; then
  print_created_stuck_recovery
  next_action="Quit Docker Desktop → wsl --shutdown → start Docker Desktop (wait green) → docker start clearread-db-1 → ./scripts/start-deps.sh"
fi

echo
echo "--- Last logs (db / minio=LocalStack / keycloak) ---"
for svc in db minio keycloak; do
  echo
  echo ">>> $svc (tail 80)"
  if ! "${COMPOSE[@]}" logs --tail=80 "$svc" 2>&1; then
    echo "(no logs for $svc — container may never have started)"
  fi
done

echo
echo "--- Health probes (host / exec) ---"
if [[ "$created_stuck" -eq 1 ]]; then
  echo "SKIP  Health probes — containers never left Created (fix Docker Desktop first)"
else
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -q "^${PROJECT}-db-"; then
    if "${COMPOSE[@]}" exec -T db pg_isready -U clearread -d clearread >/dev/null 2>&1; then
      echo "OK    Postgres pg_isready inside container (host :${CLEARREAD_DB_PORT})"
    else
      echo "FAIL  Postgres container up but pg_isready failed"
      next_action="${next_action:-Inspect db logs above; wipe volume if corrupt: docker volume rm ${PROJECT}_clearread_pg}"
    fi
  else
    echo "FAIL  Postgres container not running"
    next_action="${next_action:-./scripts/start-deps.sh}"
  fi

  if curl -sf --max-time 5 "http://127.0.0.1:${CLEARREAD_S3_PORT}/_localstack/health" >/dev/null 2>&1; then
    echo "OK    LocalStack S3 health  http://127.0.0.1:${CLEARREAD_S3_PORT}/_localstack/health"
  else
    echo "FAIL  LocalStack S3 health  http://127.0.0.1:${CLEARREAD_S3_PORT}/_localstack/health"
    next_action="${next_action:-./scripts/start-deps.sh  # Compose service name is still 'minio'}"
  fi

  if curl -sf --max-time 5 "http://127.0.0.1:${CLEARREAD_KEYCLOAK_PORT}/realms/clearread/.well-known/openid-configuration" >/dev/null 2>&1; then
    echo "OK    Keycloak OIDC discovery"
  else
    echo "FAIL  Keycloak OIDC discovery  http://127.0.0.1:${CLEARREAD_KEYCLOAK_PORT}/realms/clearread/.well-known/openid-configuration"
    next_action="${next_action:-Wait for Keycloak (1–3 min first boot) or: docker compose -p $PROJECT -f infra/compose.yaml logs --tail=80 keycloak}"
  fi

  if curl -sf --max-time 3 "http://127.0.0.1:${CLEARREAD_KEYCLOAK_MGMT_PORT}/health/ready" >/dev/null 2>&1; then
    echo "OK    Keycloak management /health/ready :${CLEARREAD_KEYCLOAK_MGMT_PORT}"
  else
    echo "WARN  Keycloak management /health/ready not ready yet (ok during first boot)"
  fi
fi

echo
echo "=== Next action ==="
if [[ -n "$next_action" ]]; then
  echo "$next_action"
  echo
  if [[ "$created_stuck" -eq 0 ]]; then
    echo "If volumes look corrupt after many failed boots:"
    echo "  ./scripts/stop.sh"
    echo "  docker volume rm ${PROJECT}_clearread_pg ${PROJECT}_clearread_minio ${PROJECT}_clearread_keycloak"
    echo "  ./scripts/start-deps.sh"
  fi
  echo "Docs: docs/ops/compose-deps-fix.md"
  exit 1
fi
echo "Deps look reachable. If the app still fails: ./scripts/check.sh"
echo "Docs: docs/ops/compose-deps-fix.md"
exit 0
