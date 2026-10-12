#!/usr/bin/env bash
# Start only infrastructure dependencies (Postgres, LocalStack S3, Keycloak).
# Starts services one-by-one with per-service status; never hangs unbounded on compose up.
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
# How long `docker compose up -d <svc>` may block before we abort.
CLEARREAD_COMPOSE_UP_TIMEOUT="${CLEARREAD_COMPOSE_UP_TIMEOUT:-90}"
# After create/up, if Status stays Created this long → Docker Desktop wedge.
CLEARREAD_CREATED_STUCK_SECONDS="${CLEARREAD_CREATED_STUCK_SECONDS:-45}"
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

print_created_stuck_recovery() {
  cat <<'EOF' >&2

!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
DIAGNOSIS: Containers stuck in Created — Docker Desktop / WSL is wedged
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
  `docker compose up` / `docker start` did not move the container out of
  Created. App Compose cannot work around a wedged Docker engine.

  If `docker start <container>` itself hangs, ONLY this fixes it:

  1. Quit Docker Desktop completely
  2. wsl --shutdown
  3. Start Docker Desktop — wait until green / Running
  4. docker start clearread-db-1   (if this hangs, repeat 1–3)
  5. ./scripts/doctor.sh && ./scripts/start-deps.sh

  Docs: docs/ops/compose-deps-fix.md#containers-stuck-in-created
!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

EOF
}

run_with_timeout() {
  local secs="$1"
  shift
  if command -v timeout >/dev/null 2>&1; then
    timeout --foreground "$secs" "$@"
    return $?
  fi
  if command -v gtimeout >/dev/null 2>&1; then
    gtimeout --foreground "$secs" "$@"
    return $?
  fi
  # Fallback: background + poll (no GNU timeout, e.g. some macOS).
  "$@" &
  local pid=$!
  local elapsed=0
  while kill -0 "$pid" 2>/dev/null; do
    if [[ "$elapsed" -ge "$secs" ]]; then
      kill "$pid" 2>/dev/null || true
      wait "$pid" 2>/dev/null || true
      return 124
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  wait "$pid"
  return $?
}

container_name_for() {
  local svc="$1"
  "${COMPOSE[@]}" ps -a --format '{{.Service}}|{{.Name}}' 2>/dev/null \
    | awk -F'|' -v s="$svc" '$1==s {print $2; exit}'
}

svc_state_line() {
  local svc="$1"
  "${COMPOSE[@]}" ps -a --format '{{.Service}} {{.State}} {{.Status}}' 2>/dev/null \
    | awk -v s="$svc" '$1==s {print; exit}'
}

is_created_state() {
  local line="$1"
  echo "$line" | grep -Eiq '(^|[[:space:]])created([[:space:]]|$)'
}

dump_inspect_state() {
  local svc="$1"
  local cname
  cname="$(container_name_for "$svc")"
  if [[ -n "$cname" ]]; then
    echo "--- docker inspect State ($cname) ---" >&2
    docker inspect --format '{{json .State}}' "$cname" 2>/dev/null >&2 || true
    echo >&2
  fi
}

fail_created_stuck() {
  local svc="$1"
  echo "ERROR: '$svc' stuck in STATUS=Created — Docker start never completed." >&2
  dump_inspect_state "$svc"
  print_created_stuck_recovery
  echo "  Diagnose: ./scripts/doctor.sh" >&2
  echo "  Docs: docs/ops/compose-deps-fix.md#containers-stuck-in-created" >&2
  exit 1
}

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
  local status
  status="$(svc_state_line "$svc")"
  if is_created_state "$status"; then
    fail_created_stuck "$svc"
  fi
  echo "ERROR: Dependency '$svc' failed to become ready." >&2
  echo "--- docker compose ps ---" >&2
  "${COMPOSE[@]}" ps -a >&2 || true
  echo "--- docker logs --tail 80 $svc ---" >&2
  "${COMPOSE[@]}" logs --tail=80 "$svc" >&2 || true
  dump_inspect_state "$svc"
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

# After create/up: fail fast if Status stays Created (wedged engine).
wait_left_created() {
  local svc="$1"
  local timeout="${2:-$CLEARREAD_CREATED_STUCK_SECONDS}"
  local elapsed=0
  local status state
  while [[ "$elapsed" -lt "$timeout" ]]; do
    status="$(svc_state_line "$svc")"
    state="$(echo "$status" | awk '{print $2}')"
    if [[ -z "$status" ]]; then
      echo "  … $svc: no container yet (${elapsed}s / ${timeout}s)"
    elif is_created_state "$status"; then
      echo "  … $svc still Created (${elapsed}s / ${timeout}s)  $status"
    else
      echo "  $svc left Created → ${state:-?} (${elapsed}s).  $status"
      return 0
    fi
    sleep 5
    elapsed=$((elapsed + 5))
  done
  status="$(svc_state_line "$svc")"
  if is_created_state "$status"; then
    fail_created_stuck "$svc"
  fi
  return 0
}

# Wait until compose reports healthy (or running for services without health yet).
wait_healthy() {
  local svc="$1"
  local timeout="${2:-$CLEARREAD_DEP_WAIT_SECONDS}"
  local elapsed=0
  echo "Waiting for $svc healthy (timeout ${timeout}s)..."
  while [[ "$elapsed" -lt "$timeout" ]]; do
    local status state
    status="$(svc_state_line "$svc")"
    state="$(echo "$status" | awk '{print $2}')"
    if is_created_state "$status"; then
      # Still Created during health wait → engine wedge (use shorter threshold remaining).
      if [[ "$elapsed" -ge "$CLEARREAD_CREATED_STUCK_SECONDS" ]]; then
        fail_created_stuck "$svc"
      fi
    fi
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
  echo "  compose up timeout=${CLEARREAD_COMPOSE_UP_TIMEOUT}s  Created-stuck threshold=${CLEARREAD_CREATED_STUCK_SECONDS}s"
  set +e
  local out rc
  out="$(run_with_timeout "$CLEARREAD_COMPOSE_UP_TIMEOUT" "${COMPOSE[@]}" up -d "$svc" 2>&1)"
  rc=$?
  set -e
  printf '%s\n' "$out"
  if [[ $rc -eq 124 ]]; then
    echo "ERROR: docker compose up -d $svc timed out after ${CLEARREAD_COMPOSE_UP_TIMEOUT}s." >&2
    echo "  Often means Docker Desktop is wedged on docker start." >&2
    dump_inspect_state "$svc"
    local status
    status="$(svc_state_line "$svc")"
    if is_created_state "$status"; then
      fail_created_stuck "$svc"
    fi
    print_created_stuck_recovery
    exit 1
  fi
  if [[ $rc -ne 0 ]]; then
    if echo "$out" | grep -Eiq 'port is already allocated|address already in use|bind.*failed|failed to bind'; then
      echo "ERROR: Could not bind a published port for $svc." >&2
      echo "  Override host ports via .env (see .env.example)." >&2
    fi
    fail_service "$svc"
  fi
  print_svc_state "$svc"
  wait_left_created "$svc"
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
echo "  Timeouts: compose-up=${CLEARREAD_COMPOSE_UP_TIMEOUT}s  created-stuck=${CLEARREAD_CREATED_STUCK_SECONDS}s  dep-healthy=${CLEARREAD_DEP_WAIT_SECONDS}s"

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
  status="$(svc_state_line keycloak)"
  if is_created_state "$status"; then
    if [[ "$elapsed" -ge "$CLEARREAD_CREATED_STUCK_SECONDS" ]]; then
      fail_created_stuck keycloak
    fi
  fi
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
