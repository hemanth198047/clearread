@echo off
setlocal EnableDelayedExpansion
set OK=0
set FAIL=0

curl -sf http://localhost:8080/v1/health >nul 2>&1
if errorlevel 1 (echo FAIL API /v1/health & set /a FAIL+=1) else (echo OK   API /v1/health & set /a OK+=1)

curl -sf http://localhost:8080/v1/capabilities >nul 2>&1
if errorlevel 1 (echo FAIL API /v1/capabilities & set /a FAIL+=1) else (echo OK   API /v1/capabilities & set /a OK+=1)

curl -sf http://localhost:8080/v1/branding/default >nul 2>&1
if errorlevel 1 (echo FAIL API /v1/branding/default & set /a FAIL+=1) else (echo OK   API /v1/branding/default & set /a OK+=1)

curl -sf http://localhost:5173/ >nul 2>&1
if errorlevel 1 (echo FAIL Web UI & set /a FAIL+=1) else (echo OK   Web UI & set /a OK+=1)

curl -sf http://localhost:8081/realms/clearread/.well-known/openid-configuration >nul 2>&1
if errorlevel 1 (echo FAIL Keycloak realm OIDC & set /a FAIL+=1) else (echo OK   Keycloak realm OIDC & set /a OK+=1)

echo.
echo Passed: %OK%  Failed: %FAIL%
if %FAIL%==0 (
  echo All checks passed.
  exit /b 0
) else (
  echo One or more checks failed. If Compose API never became healthy, use Git Bash/WSL with scripts\start.sh ^(host fallback^).
  exit /b 1
)
