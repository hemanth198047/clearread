#!/usr/bin/env bash
# Start only infrastructure dependencies (Postgres, LocalStack S3, Keycloak).
# Starts services one-by-one with per-service status; never silent-fails.
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

CLEARREAD_DB_PORT="${CLEARREAD_DB_PORT:-5433}"
CLEARREAD_S3_PORT="${CLEARREAD_S3_PORT:-9000}"
CLEARREAD_KEYCLOAK_PORT="${CLEARREAD_KEYCLOAK_PORT:-8081}"
CLEARREAD_KEYCLOAK_MGMT_PORT="${CLEARREAD_KEYCLOAK_MGMT_PORT:-9002}"
CLEARREAD_KEYCLOAK_WAIT_SECONDS="${CLEARREAD_KEYCLOAK_WAIT_SECONDS:-240}"
CLEARREAD_DEP_WAIT_SECONDS="${CLEARREAD_DEP_WAIT_SECONDS:-90}"
export CLEARREAD_DB_PORT CLEARREAD_S3_PORT CLEARREAD_KEYCLOAK_PORT CLEARREAD_KEYCLOAK_MGMT_PORT
export CLEARREAD_KEYCLOAK_WAIT_SECONDS

COMPOSE=(docker compose -p "$PROJECT" -f "$COMPOSE_FILE")
if [[ -f "$ROOT/.env" ]]; then
  COMPOSE+=(--env-file "$ROOT/.env")
fi

if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
  echo "ERROR: Docker is not running. Start Docker Desktop, then retry." >&2
  echo "  Diagnose: ./scripts/doctor.sh" >&2
  exit 1
fi

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

require_port() {
  local port="$1" name="$2" env_var="$3" suggest="$4"
  if ! port_listening "$port"; then
    return 0
  fi
  if port_owned_by_clearread "$port"; then
    echo "Port $port ($name) already published by ClearRead Compose — will re-use."
    return 0
  fi
  echo "ERROR: Port $port is in use. Stop the other process or set ${env_var}=${suggest}" >&2
  echo "  Find listener: ss -ltnp | grep :$port   OR   lsof -nP -iTCP:$port -sTCP:LISTEN" >&2
  echo "  Diagnose: ./scripts/doctor.sh" >&2
  exit 1
}

fail_service() {
  local svc="$1"
  echo "ERROR: Dependency '$svc' failed to become ready." >&2
  echo "--- docker compose ps ---" >&2
  "${COMPOSE[@]}" ps -a >&2 || true
  echo "--- docker logs --tail 80 $svc ---" >&2
  "${COMPOSE[@]}" logs --tail=80 "$svc" >&2 || true
  echo "  Diagnose: ./scripts/doctor.sh" >&2
  echo "  Docs: docs/ops/compose-deps-fix.md" >&2
  exit 1
}

print_svc_state() {
  local svc="$1"
  local line
  line="$("${COMPOSE[@]}" ps -a --format 'table {{.Service}}\t{{.State}}\t{{.Status}}' 2>/dev/null | awk -v s="$svc" 'NR==1 || $1==s')"
  echo "$line"
}

# Wait until compose reports healthy (or running for services without health yet).
wait_healthy() {
  local svc="$1"
  local timeout="${2:-$CLEARREAD_DEP_WAIT_SECONDS}"
  local elapsed=0
  echo "Waiting for $svc healthy (timeout ${timeout}s)..."
  while [[ "$elapsed" -lt "$timeout" ]]; do
    local status state
    status="$("${COMPOSE[@]}" ps -a --format '{{.Service}} {{.State}} {{.Status}}' 2>/dev/null | awk -v s="$svc" '$1==s {print; exit}')"
    state="$(echo "$status" | awk '{print $2}')"
    if echo "$status" | grep -Eiq 'healthy'; then
      echo "  $svc is healthy (${elapsed}s).  $status"
      return 0
    fi
    if echo "$state" | grep -Eiq 'exit|dead'; then
      echo "  $svc container exited." >&2
      fail_service "$svc"
    fi
    if (( elapsed % 15 == 0 )); then
      echo "  … $svc: ${status:-unknown} (${elapsed}s / ${timeout}s)"
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  fail_service "$svc"
}

start_one() {
  local svc="$1"
  echo
  echo "=== Starting $svc ==="
  set +e
  local out rc
  out="$("${COMPOSE[@]}" up -d "$svc" 2>&1)"
  rc=$?
  set -e
  printf '%s\n' "$out"
  if [[ $rc -ne 0 ]]; then
    if echo "$out" | grep -Eiq 'port is already allocated|address already in use|bind.*failed|failed to bind'; then
      echo "ERROR: Could not bind a published port for $svc." >&2
      echo "  Override host ports via .env (see .env.example)." >&2
    fi
    fail_service "$svc"
  fi
  print_svc_state "$svc"
}

require_port "$CLEARREAD_DB_PORT" "Postgres" "CLEARREAD_DB_PORT" 15432
require_port "$CLEARREAD_S3_PORT" "S3/LocalStack" "CLEARREAD_S3_PORT" 19000
require_port "$CLEARREAD_KEYCLOAK_PORT" "Keycloak" "CLEARREAD_KEYCLOAK_PORT" 18081
require_port "$CLEARREAD_KEYCLOAK_MGMT_PORT" "Keycloak mgmt" "CLEARREAD_KEYCLOAK_MGMT_PORT" 19002

mkdir -p "$ROOT/.clearread-run"
echo "$PROJECT" > "$ROOT/.clearread-run/compose.project"
echo "$COMPOSE_FILE" > "$ROOT/.clearread-run/compose.file"

echo "Starting ClearRead deps (db → LocalStack/minio → Keycloak)..."
echo "  db=:$CLEARREAD_DB_PORT  s3=:$CLEARREAD_S3_PORT  keycloak=:$CLEARREAD_KEYCLOAK_PORT  kc-mgmt=:$CLEARREAD_KEYCLOAK_MGMT_PORT"

start_one db
wait_healthy db

start_one minio
wait_healthy minio

# Keycloak is slow; start it but do not require Docker health for db/minio success.
start_one keycloak

echo
echo "Waiting for Keycloak OIDC (first boot can take 1–3 minutes on Docker Desktop)..."
kc_url="http://localhost:${CLEARREAD_KEYCLOAK_PORT}/realms/clearread/.well-known/openid-configuration"
elapsed=0
timeout="${CLEARREAD_KEYCLOAK_WAIT_SECONDS}"
while [[ "$elapsed" -lt "$timeout" ]]; do
  if curl -sf "$kc_url" >/dev/null 2>&1; then
    echo "Keycloak is ready (${elapsed}s)."
    break
  fi
  status="$("${COMPOSE[@]}" ps -a --format '{{.Service}} {{.State}} {{.Status}}' 2>/dev/null | awk '$1=="keycloak" {print; exit}')"
  if echo "$status" | grep -Eiq 'exit|dead'; then
    echo "ERROR: Keycloak container exited." >&2
    fail_service keycloak
  fi
  if (( elapsed % 15 == 0 )); then
    echo "  … Keycloak still starting (${elapsed}s / ${timeout}s)  ${status:-}"
  fi
  sleep 5
  elapsed=$((elapsed + 5))
done
if [[ "$elapsed" -ge "$timeout" ]] && ! curl -sf "$kc_url" >/dev/null 2>&1; then
  echo "ERROR: Keycloak did not become ready within ${timeout}s." >&2
  fail_service keycloak
fi

echo
echo "=== Final dependency status ==="
"${COMPOSE[@]}" ps -a db minio keycloak || true
echo
echo "Deps started (db=:$CLEARREAD_DB_PORT s3/LocalStack=:$CLEARREAD_S3_PORT keycloak=:$CLEARREAD_KEYCLOAK_PORT)."
echo "  Note: Compose service name for object storage is still 'minio' (LocalStack image)."
echo "API/web: ./scripts/start.sh   Diagnose: ./scripts/doctor.sh"
