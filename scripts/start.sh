#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILE="$ROOT/infra/compose.yaml"
PROJECT="clearread"
PID_DIR="$ROOT/.clearread-run"
mkdir -p "$PID_DIR"

echo "Starting ClearRead Compose stack (project=$PROJECT)..."
docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d --build db minio keycloak api web

echo "$PROJECT" > "$PID_DIR/compose.project"
echo "$COMPOSE_FILE" > "$PID_DIR/compose.file"

echo "Waiting for API health..."
api_up=0
for i in $(seq 1 90); do
  if curl -sf http://localhost:8080/v1/health >/dev/null 2>&1; then
    echo "API is up (Compose)."
    api_up=1
    break
  fi
  sleep 2
done

if [[ "$api_up" -ne 1 ]]; then
  echo "Compose API not healthy — falling back to host API against published deps..." >&2
  docker compose -p "$PROJECT" -f "$COMPOSE_FILE" stop api web >/dev/null 2>&1 || true
  (
    cd "$ROOT/backend"
    export CLEARREAD_DB_URL='jdbc:postgresql://localhost:5432/clearread'
    export CLEARREAD_DB_USER=clearread
    export CLEARREAD_DB_PASSWORD=clearread
    export CLEARREAD_S3_ENDPOINT='http://localhost:9000'
    export CLEARREAD_S3_ACCESS_KEY=test
    export CLEARREAD_S3_SECRET_KEY=test
    export CLEARREAD_S3_BUCKET=clearread
    export CLEARREAD_OIDC_ISSUER='http://localhost:8081/realms/clearread'
    export CLEARREAD_OIDC_JWK_SET_URI='http://localhost:8081/realms/clearread/protocol/openid-connect/certs'
    export CLEARREAD_CORS_ORIGINS='http://localhost:5173,http://localhost:3000'
    nohup ./gradlew bootRun --no-daemon >"$PID_DIR/api.log" 2>&1 &
    echo $! >"$PID_DIR/api.pid"
  )
  (
    cd "$ROOT/frontend"
    nohup npm run dev -- --host 0.0.0.0 --port 5173 >"$PID_DIR/web.log" 2>&1 &
    echo $! >"$PID_DIR/web.pid"
  )
  for i in $(seq 1 90); do
    if curl -sf http://localhost:8080/v1/health >/dev/null 2>&1; then
      echo "API is up (host fallback)."
      api_up=1
      break
    fi
    sleep 2
  done
fi

if [[ "$api_up" -ne 1 ]]; then
  echo "API did not become healthy. Check Compose logs or $PID_DIR/api.log" >&2
  exit 1
fi

echo
echo "ClearRead started."
echo "  Web:      http://localhost:5173"
echo "  API:      http://localhost:8080"
echo "  Keycloak: http://localhost:8081 (admin/admin)"
echo "  Demo user: demo / demo"
echo "Stop with: ./scripts/stop.sh"
