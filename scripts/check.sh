#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -f "$ROOT/.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/.env"
  set +a
fi

CLEARREAD_API_PORT="${CLEARREAD_API_PORT:-8080}"
CLEARREAD_WEB_PORT="${CLEARREAD_WEB_PORT:-5173}"
CLEARREAD_KEYCLOAK_PORT="${CLEARREAD_KEYCLOAK_PORT:-8081}"

ok=0
fail=0

check() {
  local name="$1"
  shift
  if "$@"; then
    echo "OK  $name"
    ok=$((ok + 1))
  else
    echo "FAIL $name"
    fail=$((fail + 1))
  fi
}

check "API /v1/health" curl -sf "http://localhost:${CLEARREAD_API_PORT}/v1/health" >/dev/null
check "API /v1/capabilities" curl -sf "http://localhost:${CLEARREAD_API_PORT}/v1/capabilities" >/dev/null
check "API /v1/branding/default" curl -sf "http://localhost:${CLEARREAD_API_PORT}/v1/branding/default" >/dev/null
check "Web UI" curl -sf "http://localhost:${CLEARREAD_WEB_PORT}/" >/dev/null
check "Keycloak realm OIDC" curl -sf "http://localhost:${CLEARREAD_KEYCLOAK_PORT}/realms/clearread/.well-known/openid-configuration" >/dev/null

echo
echo "Passed: $ok  Failed: $fail"
[[ "$fail" -eq 0 ]]
