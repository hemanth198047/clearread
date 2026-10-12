#!/usr/bin/env bash
# Start only infrastructure dependencies (Postgres, LocalStack S3, Keycloak).
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

CLEARREAD_DB_PORT="${CLEARREAD_DB_PORT:-5432}"
CLEARREAD_S3_PORT="${CLEARREAD_S3_PORT:-9000}"
CLEARREAD_KEYCLOAK_PORT="${CLEARREAD_KEYCLOAK_PORT:-8081}"
CLEARREAD_KEYCLOAK_WAIT_SECONDS="${CLEARREAD_KEYCLOAK_WAIT_SECONDS:-240}"
export CLEARREAD_DB_PORT CLEARREAD_S3_PORT CLEARREAD_KEYCLOAK_PORT CLEARREAD_KEYCLOAK_WAIT_SECONDS

COMPOSE=(docker compose -p "$PROJECT" -f "$COMPOSE_FILE")
if [[ -f "$ROOT/.env" ]]; then
  COMPOSE+=(--env-file "$ROOT/.env")
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
  exit 1
}

require_port "$CLEARREAD_DB_PORT" "Postgres" "CLEARREAD_DB_PORT" 15432
require_port "$CLEARREAD_S3_PORT" "S3/LocalStack" "CLEARREAD_S3_PORT" 19000
require_port "$CLEARREAD_KEYCLOAK_PORT" "Keycloak" "CLEARREAD_KEYCLOAK_PORT" 18081

mkdir -p "$ROOT/.clearread-run"
echo "$PROJECT" > "$ROOT/.clearread-run/compose.project"
echo "$COMPOSE_FILE" > "$ROOT/.clearread-run/compose.file"
"${COMPOSE[@]}" up -d db minio keycloak

echo "Waiting for Keycloak (first boot can take 1–3 minutes on Docker Desktop)..."
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
    "${COMPOSE[@]}" logs --tail=60 keycloak >&2 || true
    echo "  See docs/ops/keycloak-startup.md" >&2
    exit 1
  fi
  if (( elapsed % 15 == 0 )); then
    echo "  … Keycloak still starting (${elapsed}s / ${timeout}s)"
  fi
  sleep 5
  elapsed=$((elapsed + 5))
done
if [[ "$elapsed" -ge "$timeout" ]] && ! curl -sf "$kc_url" >/dev/null 2>&1; then
  echo "ERROR: Keycloak did not become ready within ${timeout}s." >&2
  "${COMPOSE[@]}" logs --tail=80 keycloak >&2 || true
  echo "  docker logs clearread-keycloak-1" >&2
  echo "  Give Docker Desktop more RAM (4GB+). See docs/ops/keycloak-startup.md" >&2
  exit 1
fi

echo "Deps started (db=:$CLEARREAD_DB_PORT s3=:$CLEARREAD_S3_PORT keycloak=:$CLEARREAD_KEYCLOAK_PORT)."
echo "API/web: see README hybrid section or ./scripts/start.sh"
