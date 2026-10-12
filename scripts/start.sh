#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILE="$ROOT/infra/compose.yaml"
PROJECT="clearread"
PID_DIR="$ROOT/.clearread-run"
mkdir -p "$PID_DIR"

# Load repo-root .env into the shell (Compose also reads it from CWD for ${VAR} substitution).
if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

CLEARREAD_API_PORT="${CLEARREAD_API_PORT:-8080}"
CLEARREAD_WEB_PORT="${CLEARREAD_WEB_PORT:-5173}"
CLEARREAD_KEYCLOAK_PORT="${CLEARREAD_KEYCLOAK_PORT:-8081}"
CLEARREAD_DB_PORT="${CLEARREAD_DB_PORT:-5432}"
CLEARREAD_S3_PORT="${CLEARREAD_S3_PORT:-9000}"

export CLEARREAD_API_PORT CLEARREAD_WEB_PORT CLEARREAD_KEYCLOAK_PORT CLEARREAD_DB_PORT CLEARREAD_S3_PORT
export VITE_API_BASE_URL="${VITE_API_BASE_URL:-http://localhost:${CLEARREAD_API_PORT}}"
export VITE_OIDC_AUTHORITY="${VITE_OIDC_AUTHORITY:-http://localhost:${CLEARREAD_KEYCLOAK_PORT}/realms/clearread}"
export CLEARREAD_OIDC_ISSUER="${CLEARREAD_OIDC_ISSUER:-http://localhost:${CLEARREAD_KEYCLOAK_PORT}/realms/clearread}"
export CLEARREAD_CORS_ORIGINS="${CLEARREAD_CORS_ORIGINS:-http://localhost:${CLEARREAD_WEB_PORT},http://localhost:3000}"
export CLEARREAD_API_ORIGIN="${CLEARREAD_API_ORIGIN:-http://localhost:${CLEARREAD_API_PORT}}"
export CLEARREAD_OIDC_ORIGIN="${CLEARREAD_OIDC_ORIGIN:-http://localhost:${CLEARREAD_KEYCLOAK_PORT}}"

COMPOSE=(docker compose -p "$PROJECT" -f "$COMPOSE_FILE")
if [[ -f "$ROOT/.env" ]]; then
  COMPOSE+=(--env-file "$ROOT/.env")
fi

port_listening() {
  local port="$1"
  if command -v ss >/dev/null 2>&1; then
    ss -ltn "( sport = :$port )" 2>/dev/null | grep -q LISTEN && return 0
    # Some ss builds ignore the filter expression above; fall back to a line scan.
    ss -ltn 2>/dev/null | awk -v p=":$port" '$4 ~ p"$" { found=1 } END { exit !found }' && return 0
    return 1
  fi
  if command -v lsof >/dev/null 2>&1; then
    lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 && return 0
    return 1
  fi
  # Bash /dev/tcp: success means something accepted the connection.
  (echo >/dev/tcp/127.0.0.1/"$port") >/dev/null 2>&1
}

# True when the listener is already our ClearRead Compose publish (safe to re-up).
port_owned_by_clearread() {
  local port="$1"
  docker ps --format '{{.Names}} {{.Ports}}' 2>/dev/null \
    | grep -E "^${PROJECT}-" \
    | grep -E "(^|[^0-9])${port}->" >/dev/null 2>&1
}

require_port_free_or_ours() {
  local port="$1"
  local name="$2"
  local env_var="$3"
  local hint="${4:-}"

  if ! port_listening "$port"; then
    return 0
  fi
  if port_owned_by_clearread "$port"; then
    echo "Port $port ($name) already published by ClearRead Compose — will re-use."
    return 0
  fi

  local suggest="$port"
  case "$env_var" in
    CLEARREAD_API_PORT) suggest=18080 ;;
    CLEARREAD_WEB_PORT) suggest=5174 ;;
    CLEARREAD_KEYCLOAK_PORT) suggest=18081 ;;
    CLEARREAD_DB_PORT) suggest=15432 ;;
    CLEARREAD_S3_PORT) suggest=19000 ;;
  esac
  echo "ERROR: Port $port is in use. Stop the other process or set ${env_var}=${suggest}" >&2
  if [[ -n "$hint" ]]; then
    echo "  $hint" >&2
  fi
  echo "  Find listener (Linux/macOS): ss -ltnp | grep :$port   OR   lsof -nP -iTCP:$port -sTCP:LISTEN" >&2
  echo "  Free ClearRead leftovers: ./scripts/stop.sh" >&2
  echo "  Override example: echo '${env_var}=${suggest}' >> .env && ./scripts/start.sh" >&2
  exit 1
}

compose_up_checked() {
  local label="$1"
  shift
  local out
  set +e
  out="$("${COMPOSE[@]}" up -d "$@" 2>&1)"
  local rc=$?
  set -e
  printf '%s\n' "$out"
  if [[ $rc -ne 0 ]]; then
    if echo "$out" | grep -Eiq 'port is already allocated|address already in use|bind.*failed|failed to bind'; then
      echo "ERROR: Compose could not bind a published port ($label)." >&2
      echo "  Inspect: docker ps --format 'table {{.Names}}\t{{.Ports}}'" >&2
      echo "  Override host ports via .env (see .env.example): CLEARREAD_API_PORT, CLEARREAD_WEB_PORT, …" >&2
      echo "  Or free ClearRead leftovers: ./scripts/stop.sh" >&2
      exit 1
    fi
    echo "ERROR: docker compose up failed for: $*" >&2
    exit "$rc"
  fi
}

api_container_failed() {
  local status
  status="$("${COMPOSE[@]}" ps -a --format '{{.Service}} {{.State}} {{.Status}}' 2>/dev/null | awk '$1=="api" {print; exit}')"
  [[ -z "$status" ]] && return 1
  echo "$status" | grep -Eiq 'exit|dead|restarting' && return 0
  # Not created / not running after a bind failure
  echo "$status" | grep -Eiq 'created|stopped' && return 0
  return 1
}

# Ensure wrapper is executable on host (hybrid fallback)
if [[ -f "$ROOT/backend/gradlew" ]]; then
  chmod +x "$ROOT/backend/gradlew" || true
  if command -v sed >/dev/null 2>&1; then
    sed -i 's/\r$//' "$ROOT/backend/gradlew" 2>/dev/null || true
  fi
fi

echo "Checking published ports..."
require_port_free_or_ours "$CLEARREAD_API_PORT" "API" "CLEARREAD_API_PORT"
require_port_free_or_ours "$CLEARREAD_WEB_PORT" "Web" "CLEARREAD_WEB_PORT"
require_port_free_or_ours "$CLEARREAD_KEYCLOAK_PORT" "Keycloak" "CLEARREAD_KEYCLOAK_PORT"
require_port_free_or_ours "$CLEARREAD_DB_PORT" "Postgres" "CLEARREAD_DB_PORT"
require_port_free_or_ours "$CLEARREAD_S3_PORT" "S3/LocalStack" "CLEARREAD_S3_PORT"

echo "Building ClearRead images (project=$PROJECT)..."
echo "  API host port=$CLEARREAD_API_PORT  Web host port=$CLEARREAD_WEB_PORT  VITE_API_BASE_URL=$VITE_API_BASE_URL"
if ! "${COMPOSE[@]}" build api web; then
  echo "ERROR: docker compose build failed." >&2
  echo "  API image copies Gradle from gradle:8.14.3-jdk21 (no distribution zip download)." >&2
  echo "  If you saw Failed to load native library 'libnative-platform.so':" >&2
  echo "    git pull && docker builder prune -f" >&2
  echo "    docker compose -p $PROJECT -f \"$COMPOSE_FILE\" build --no-cache api" >&2
  echo "  If you saw SocketTimeoutException / gradle-*-bin.zip timeout:" >&2
  echo "    docker pull gradle:8.14.3-jdk21 && docker compose -p $PROJECT -f \"$COMPOSE_FILE\" build api" >&2
  echo "    or host: (cd backend && ./gradlew bootJar --no-daemon)" >&2
  echo "  Common exit 127 cause: gradlew not executable / CRLF line endings." >&2
  echo "  See docs/ops/compose-build-fix.md" >&2
  exit 1
fi

echo "Starting ClearRead Compose stack..."
# Prefer full stack; if API never becomes healthy (e.g. broken container bridge networking),
# fall through to host API + Vite against published dependency ports.
compose_up_checked "deps" db minio keycloak
compose_up_checked "api/web" api web

echo "$PROJECT" > "$PID_DIR/compose.project"
echo "$COMPOSE_FILE" > "$PID_DIR/compose.file"

echo "Waiting for API health (http://localhost:${CLEARREAD_API_PORT}/v1/health)..."
api_up=0
HEALTH_ATTEMPTS="${CLEARREAD_HEALTH_ATTEMPTS:-45}"
for i in $(seq 1 "$HEALTH_ATTEMPTS"); do
  if curl -sf "http://localhost:${CLEARREAD_API_PORT}/v1/health" >/dev/null 2>&1; then
    echo "API is up (Compose)."
    api_up=1
    break
  fi
  if api_container_failed; then
    echo "API container is not running — stopping health wait early." >&2
    "${COMPOSE[@]}" ps -a api >&2 || true
    "${COMPOSE[@]}" logs --tail=40 api >&2 || true
    break
  fi
  if [[ $i -eq 10 ]] || [[ $i -eq 25 ]]; then
    "${COMPOSE[@]}" ps api || true
    "${COMPOSE[@]}" logs --tail=40 api || true
  fi
  sleep 2
done

if [[ "$api_up" -ne 1 ]]; then
  echo "Compose API not healthy — falling back to host API against published deps..." >&2
  "${COMPOSE[@]}" stop api web >/dev/null 2>&1 || true
  if port_listening "$CLEARREAD_API_PORT" && ! port_owned_by_clearread "$CLEARREAD_API_PORT"; then
    echo "ERROR: Port $CLEARREAD_API_PORT is in use. Stop the other process or set CLEARREAD_API_PORT=18080" >&2
    exit 1
  fi
  if port_listening "$CLEARREAD_WEB_PORT" && ! port_owned_by_clearread "$CLEARREAD_WEB_PORT"; then
    echo "ERROR: Port $CLEARREAD_WEB_PORT is in use. Stop the other process or set CLEARREAD_WEB_PORT=5174" >&2
    exit 1
  fi
  (
    cd "$ROOT/backend"
    chmod +x ./gradlew || true
    export CLEARREAD_DB_URL="jdbc:postgresql://localhost:${CLEARREAD_DB_PORT}/clearread"
    export CLEARREAD_DB_USER=clearread
    export CLEARREAD_DB_PASSWORD=clearread
    export CLEARREAD_S3_ENDPOINT="http://localhost:${CLEARREAD_S3_PORT}"
    export CLEARREAD_S3_ACCESS_KEY=test
    export CLEARREAD_S3_SECRET_KEY=test
    export CLEARREAD_S3_BUCKET=clearread
    export CLEARREAD_OIDC_ISSUER
    export CLEARREAD_OIDC_JWK_SET_URI="http://localhost:${CLEARREAD_KEYCLOAK_PORT}/realms/clearread/protocol/openid-connect/certs"
    export CLEARREAD_CORS_ORIGINS
    export SERVER_PORT="$CLEARREAD_API_PORT"
    nohup ./gradlew bootRun --no-daemon >"$PID_DIR/api.log" 2>&1 &
    echo $! >"$PID_DIR/api.pid"
  )
  (
    cd "$ROOT/frontend"
    if [[ ! -d node_modules ]]; then
      npm install
    fi
    export VITE_API_BASE_URL
    export VITE_OIDC_AUTHORITY
    nohup npm run dev -- --host 0.0.0.0 --port "$CLEARREAD_WEB_PORT" >"$PID_DIR/web.log" 2>&1 &
    echo $! >"$PID_DIR/web.pid"
  )
  for i in $(seq 1 "$HEALTH_ATTEMPTS"); do
    if curl -sf "http://localhost:${CLEARREAD_API_PORT}/v1/health" >/dev/null 2>&1; then
      echo "API is up (host fallback)."
      api_up=1
      break
    fi
    sleep 2
  done
fi

if [[ "$api_up" -ne 1 ]]; then
  echo "ERROR: API did not become healthy after Compose and host fallback (~$((HEALTH_ATTEMPTS * 2))s)." >&2
  echo "  Compose logs:" >&2
  "${COMPOSE[@]}" logs --tail=80 api >&2 || true
  echo "  Host API log (if present): $PID_DIR/api.log" >&2
  echo "  Common causes: port conflict (set CLEARREAD_API_PORT), broken Docker bridge," >&2
  echo "  gradlew CRLF/exit 127 (see docs/ops/compose-build-fix.md), or DB not ready." >&2
  echo "  Stop leftovers: ./scripts/stop.sh" >&2
  exit 1
fi

echo
echo "ClearRead started."
echo "  Web:      http://localhost:${CLEARREAD_WEB_PORT}"
echo "  API:      http://localhost:${CLEARREAD_API_PORT}"
echo "  Keycloak: http://localhost:${CLEARREAD_KEYCLOAK_PORT} (console admin/admin on master)"
echo "  Demo accounts (ClearRead realm):"
echo "    admin / admin  — workspace admin"
echo "    user  / user   — standard member (author)"
echo "    demo  / demo   — legacy admin (compat)"
echo "  Sign up: open Web → Create account (Keycloak self-registration)"
echo "Smoke: ./scripts/check.sh"
echo "Stop with: ./scripts/stop.sh"
echo
echo "Verified local commands:"
echo "  docker compose -p clearread -f infra/compose.yaml build api web"
echo "  docker compose -p clearread -f infra/compose.yaml up -d"
echo "  ./scripts/start.sh"
