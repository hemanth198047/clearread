#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

COMPOSE_FILE="$ROOT/infra/compose.yaml"
PROJECT="clearread"
PID_DIR="$ROOT/.clearread-run"
mkdir -p "$PID_DIR"

echo "Starting ClearRead Compose stack (project=$PROJECT)..."
docker compose -p "$PROJECT" -f "$COMPOSE_FILE" up -d --build

echo "$PROJECT" > "$PID_DIR/compose.project"
echo "$COMPOSE_FILE" > "$PID_DIR/compose.file"

echo "Waiting for API health..."
for i in $(seq 1 60); do
  if curl -sf http://localhost:8080/v1/health >/dev/null 2>&1; then
    echo "API is up."
    break
  fi
  sleep 2
  if [[ "$i" -eq 60 ]]; then
    echo "API did not become healthy in time. Check: docker compose -p $PROJECT -f $COMPOSE_FILE logs api" >&2
    exit 1
  fi
done

echo
echo "ClearRead started."
echo "  Web:      http://localhost:5173"
echo "  API:      http://localhost:8080"
echo "  Keycloak: http://localhost:8081 (admin/admin)"
echo "  Demo user: demo / demo"
echo "Stop with: ./scripts/stop.sh"
