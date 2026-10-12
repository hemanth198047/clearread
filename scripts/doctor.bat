@echo off
setlocal EnableDelayedExpansion
set ROOT=%~dp0..
cd /d "%ROOT%"
set PROJECT=clearread
set COMPOSE_FILE=%ROOT%\infra\compose.yaml
set EXITCODE=0
set NEXT=

if exist "%ROOT%\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%\.env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

if not defined CLEARREAD_API_PORT set CLEARREAD_API_PORT=8080
if not defined CLEARREAD_WEB_PORT set CLEARREAD_WEB_PORT=5173
if not defined CLEARREAD_KEYCLOAK_PORT set CLEARREAD_KEYCLOAK_PORT=8081
if not defined CLEARREAD_KEYCLOAK_MGMT_PORT set CLEARREAD_KEYCLOAK_MGMT_PORT=9002
if not defined CLEARREAD_DB_PORT set CLEARREAD_DB_PORT=5433
if not defined CLEARREAD_S3_PORT set CLEARREAD_S3_PORT=9000

set COMPOSE_ENVFILE=
if exist "%ROOT%\.env" set COMPOSE_ENVFILE=--env-file "%ROOT%\.env"

echo === ClearRead doctor ===
echo Repo: %ROOT%
echo Compose file: %COMPOSE_FILE%
echo Project: %PROJECT%
echo.

where docker >nul 2>&1
if errorlevel 1 (
  echo FAIL  Docker CLI not found on PATH
  echo.
  echo Next action: Install Docker Desktop, ensure it is running, then re-run scripts\doctor.bat
  exit /b 1
)
for /f "delims=" %%V in ('docker --version 2^>nul') do echo OK    Docker CLI present: %%V

docker info >nul 2>&1
if errorlevel 1 (
  echo FAIL  Docker daemon not reachable ^(is Docker Desktop running?^)
  echo.
  echo Next action: Start Docker Desktop, wait until it is healthy, then re-run scripts\doctor.bat
  exit /b 1
)
echo OK    Docker daemon reachable

docker compose version >nul 2>&1
if errorlevel 1 (
  echo FAIL  Docker Compose V2 plugin missing ^(need: docker compose ...^)
  echo.
  echo Next action: Update Docker Desktop / install Compose V2, then retry
  exit /b 1
)
for /f "delims=" %%V in ('docker compose version 2^>nul') do echo OK    %%V

echo.
echo --- Host ports ^(defaults / .env^) ---
echo   API=%CLEARREAD_API_PORT%  Web=%CLEARREAD_WEB_PORT%  Keycloak=%CLEARREAD_KEYCLOAK_PORT%  KC-mgmt=%CLEARREAD_KEYCLOAK_MGMT_PORT%  Postgres=%CLEARREAD_DB_PORT%  S3/LocalStack=%CLEARREAD_S3_PORT%

call :check_port %CLEARREAD_DB_PORT% Postgres CLEARREAD_DB_PORT 15432
call :check_port %CLEARREAD_S3_PORT% S3/LocalStack CLEARREAD_S3_PORT 19000
call :check_port %CLEARREAD_KEYCLOAK_PORT% Keycloak CLEARREAD_KEYCLOAK_PORT 18081
call :check_port %CLEARREAD_KEYCLOAK_MGMT_PORT% Keycloak-mgmt CLEARREAD_KEYCLOAK_MGMT_PORT 19002
call :check_port %CLEARREAD_API_PORT% API CLEARREAD_API_PORT 18080
call :check_port %CLEARREAD_WEB_PORT% Web CLEARREAD_WEB_PORT 5174

echo.
echo --- docker compose ps ---
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a
if errorlevel 1 echo ^(compose ps failed — project may not exist yet^)

echo.
echo --- Last logs ^(db / minio=LocalStack / keycloak^) ---
echo.
echo ^>^>^> db ^(tail 80^)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% logs --tail=80 db 2>&1
echo.
echo ^>^>^> minio/LocalStack ^(tail 80^)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% logs --tail=80 minio 2>&1
echo.
echo ^>^>^> keycloak ^(tail 80^)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% logs --tail=80 keycloak 2>&1

echo.
echo --- Health probes ---
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% exec -T db pg_isready -U clearread -d clearread >nul 2>&1
if errorlevel 1 (
  echo FAIL  Postgres pg_isready
  if "!NEXT!"=="" set "NEXT=scripts\start-deps.bat"
  set EXITCODE=1
) else (
  echo OK    Postgres pg_isready inside container ^(host :%CLEARREAD_DB_PORT%^)
)

curl -sf --max-time 5 "http://127.0.0.1:%CLEARREAD_S3_PORT%/_localstack/health" >nul 2>&1
if errorlevel 1 (
  echo FAIL  LocalStack S3 health  http://127.0.0.1:%CLEARREAD_S3_PORT%/_localstack/health
  if "!NEXT!"=="" set "NEXT=scripts\start-deps.bat  (Compose service name is still minio)"
  set EXITCODE=1
) else (
  echo OK    LocalStack S3 health
)

curl -sf --max-time 5 "http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread/.well-known/openid-configuration" >nul 2>&1
if errorlevel 1 (
  echo FAIL  Keycloak OIDC discovery
  if "!NEXT!"=="" set "NEXT=Wait for Keycloak ^(1-3 min first boot^) or: docker compose -p %PROJECT% -f infra\compose.yaml logs --tail=80 keycloak"
  set EXITCODE=1
) else (
  echo OK    Keycloak OIDC discovery
)

curl -sf --max-time 3 "http://127.0.0.1:%CLEARREAD_KEYCLOAK_MGMT_PORT%/health/ready" >nul 2>&1
if errorlevel 1 (
  echo WARN  Keycloak management /health/ready not ready yet ^(ok during first boot^)
) else (
  echo OK    Keycloak management /health/ready :%CLEARREAD_KEYCLOAK_MGMT_PORT%
)

echo.
echo === Next action ===
if not "!NEXT!"=="" (
  echo !NEXT!
  echo.
  echo If volumes look corrupt after many failed boots:
  echo   scripts\stop.bat
  echo   docker volume rm %PROJECT%_clearread_pg %PROJECT%_clearread_minio %PROJECT%_clearread_keycloak
  echo   scripts\start-deps.bat
  echo Docs: docs\ops\compose-deps-fix.md
  endlocal & exit /b 1
)
if %EXITCODE% NEQ 0 (
  echo scripts\start-deps.bat
  echo Docs: docs\ops\compose-deps-fix.md
  endlocal & exit /b 1
)
echo Deps look reachable. If the app still fails: scripts\check.bat
echo Docs: docs\ops\compose-deps-fix.md
endlocal
exit /b 0

:check_port
set PORT=%~1
set NAME=%~2
set ENVVAR=%~3
set SUGGEST=%~4
netstat -ano | findstr ":%PORT% " | findstr /I "LISTENING" >nul
if errorlevel 1 (
  echo OK    Port %PORT% ^(%NAME%^) is free
  exit /b 0
)
docker ps --format "{{.Names}} {{.Ports}}" 2>nul | findstr /I "%PROJECT%-" | findstr /C:"%PORT%->" >nul
if not errorlevel 1 (
  echo OK    Port %PORT% ^(%NAME%^) owned by ClearRead Compose
  exit /b 0
)
echo WARN  Port %PORT% ^(%NAME%^) is in use by another process
echo       Set %ENVVAR%=%SUGGEST% in .env  ^(see .env.example^)
if "!NEXT!"=="" set "NEXT=Free port %PORT% or set %ENVVAR%=%SUGGEST% in .env, then scripts\start-deps.bat"
set EXITCODE=1
exit /b 0
