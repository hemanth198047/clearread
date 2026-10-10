#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILE="$ROOT/infra/compose.yaml"
PROJECT="clearread"
PID_DIR="$ROOT/.clearread-run"
mkdir -p "$PID_DIR"

# Ensure wrapper is executable on host (hybrid fallback)
if [[ -f "$ROOT/backend/gradlew" ]]; then
  chmod +x "$ROOT/backend/gradlew" || true
  # Strip CRLF if present (safe no-op on LF)
  if command -v sed >/dev/null 2>&1; then
    sed -i 's/\r$//' "$ROOT/backend/gradlew" 2>/dev/null || true
  fi
fi

echo "Building ClearRead images (project=$PROJECT)..."
if ! docker compose -p "$PROJECT" -f "$COMPOSE_FILE" build api web; then
  echo "ERROR: docker compose build failed." >&2
  echo "  API Dockerfile runs: chmod +x gradlew && ./gradlew bootJar" >&2
  echo "  Common exit 127 cause: gradlew not executable / CRLF line endings." >&2
  echo "  Try: (cd backend && chmod +x gradlew && ./gradlew bootJar --no-daemon)" >&2
  exit 1
fi

echo "Starting ClearRead Compose stack..."
docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d db minio keycloak api web

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
  # Surface container crash loops early
  if [[ $i -eq 15 ]] || [[ $i -eq 45 ]]; then
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" ps api || true
    docker compose -p "$PROJECT" -f "$COMPOSE_FILE" logs --tail=40 api || true
  fi
  sleep 2
done

if [[ "$api_up" -ne 1 ]]; then
  echo "Compose API not healthy — falling back to host API against published deps..." >&2
  docker compose -p "$PROJECT" -f "$COMPOSE_FILE" stop api web >/dev/null 2>&1 || true
  (
    cd "$ROOT/backend"
    chmod +x ./gradlew || true
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
    if [[ ! -d node_modules ]]; then
      npm install
    fi
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
  docker compose -p "$PROJECT" -f "$COMPOSE_FILE" logs --tail=80 api >&2 || true
  exit 1
fi

echo
echo "ClearRead started."
echo "  Web:      http://localhost:5173"
echo "  API:      http://localhost:8080"
echo "  Keycloak: http://localhost:8081 (admin/admin)"
echo "  Demo user: demo / demo"
echo "Stop with: ./scripts/stop.sh"
echo
echo "Verified local commands:"
echo "  docker compose -p clearread -f infra/compose.yaml build api web"
echo "  docker compose -p clearread -f infra/compose.yaml up -d"
echo "  ./scripts/start.sh"
