#!/usr/bin/env bash
set -euo pipefail

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

check "API /v1/health" curl -sf http://localhost:8080/v1/health >/dev/null
check "API /v1/capabilities" curl -sf http://localhost:8080/v1/capabilities >/dev/null
check "API /v1/branding/default" curl -sf http://localhost:8080/v1/branding/default >/dev/null
check "Web UI" curl -sf http://localhost:5173/ >/dev/null
check "Keycloak realm OIDC" curl -sf http://localhost:8081/realms/clearread/.well-known/openid-configuration >/dev/null

echo
echo "Passed: $ok  Failed: $fail"
[[ "$fail" -eq 0 ]]
