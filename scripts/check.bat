@echo off
setlocal EnableDelayedExpansion
set ROOT=%~dp0..
cd /d "%ROOT%"

if exist "%ROOT%\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%\.env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

if not defined CLEARREAD_API_PORT set CLEARREAD_API_PORT=8080
if not defined CLEARREAD_WEB_PORT set CLEARREAD_WEB_PORT=5173
if not defined CLEARREAD_KEYCLOAK_PORT set CLEARREAD_KEYCLOAK_PORT=8081

set OK=0
set FAIL=0

python -c "import json;from pathlib import Path;r=json.loads(Path('infra/keycloak/realm-clearread.json').read_text());assert r.get('registrationAllowed') is True;u={x['username'] for x in r.get('users',[])};assert {'admin','user','demo'}<=u" >nul 2>&1
if errorlevel 1 (echo FAIL Realm seed (admin/user/demo + registration) & set /a FAIL+=1) else (echo OK   Realm seed (admin/user/demo + registration) & set /a OK+=1)

curl -sf http://localhost:%CLEARREAD_API_PORT%/v1/health >nul 2>&1
if errorlevel 1 (echo FAIL API /v1/health & set /a FAIL+=1) else (echo OK   API /v1/health & set /a OK+=1)

curl -sf http://localhost:%CLEARREAD_API_PORT%/v1/capabilities >nul 2>&1
if errorlevel 1 (echo FAIL API /v1/capabilities & set /a FAIL+=1) else (echo OK   API /v1/capabilities & set /a OK+=1)

curl -sf http://localhost:%CLEARREAD_API_PORT%/v1/branding/default >nul 2>&1
if errorlevel 1 (echo FAIL API /v1/branding/default & set /a FAIL+=1) else (echo OK   API /v1/branding/default & set /a OK+=1)

curl -sf http://localhost:%CLEARREAD_API_PORT%/v1/branding/default 2>nul | findstr /C:"showDemoAccounts" >nul
if errorlevel 1 (echo FAIL Branding shows demo accounts flag & set /a FAIL+=1) else (echo OK   Branding shows demo accounts flag & set /a OK+=1)

curl -sf http://localhost:%CLEARREAD_WEB_PORT%/ >nul 2>&1
if errorlevel 1 (echo FAIL Web UI & set /a FAIL+=1) else (echo OK   Web UI & set /a OK+=1)

curl -sf http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread/.well-known/openid-configuration >nul 2>&1
if errorlevel 1 (echo FAIL Keycloak realm OIDC & set /a FAIL+=1) else (echo OK   Keycloak realm OIDC & set /a OK+=1)

echo.
echo Passed: %OK%  Failed: %FAIL%
if %FAIL%==0 (
  echo All checks passed.
  exit /b 0
) else (
  echo One or more checks failed. If Compose API never became healthy, use Git Bash/WSL with scripts\start.sh ^(host fallback^).
  echo Confirm ports in .env match a running stack ^(CLEARREAD_API_PORT / CLEARREAD_WEB_PORT^).
  exit /b 1
)
