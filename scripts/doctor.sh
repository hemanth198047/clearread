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

echo
echo "=== Next action ==="
if [[ -n "$next_action" ]]; then
  echo "$next_action"
  echo
  echo "If volumes look corrupt after many failed boots:"
  echo "  ./scripts/stop.sh"
  echo "  docker volume rm ${PROJECT}_clearread_pg ${PROJECT}_clearread_minio ${PROJECT}_clearread_keycloak"
  echo "  ./scripts/start-deps.sh"
  echo "Docs: docs/ops/compose-deps-fix.md"
  exit 1
fi
echo "Deps look reachable. If the app still fails: ./scripts/check.sh"
echo "Docs: docs/ops/compose-deps-fix.md"
exit 0
