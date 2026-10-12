@echo off
setlocal EnableDelayedExpansion
set ROOT=%~dp0..
cd /d "%ROOT%"
set PROJECT=clearread
set COMPOSE_FILE=%ROOT%\infra\compose.yaml
if not exist "%ROOT%\.clearread-run" mkdir "%ROOT%\.clearread-run"

if exist "%ROOT%\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%\.env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

if not defined CLEARREAD_DB_PORT set CLEARREAD_DB_PORT=5433
if not defined CLEARREAD_S3_PORT set CLEARREAD_S3_PORT=9000
if not defined CLEARREAD_KEYCLOAK_PORT set CLEARREAD_KEYCLOAK_PORT=8081
if not defined CLEARREAD_KEYCLOAK_MGMT_PORT set CLEARREAD_KEYCLOAK_MGMT_PORT=9002
if not defined CLEARREAD_KEYCLOAK_WAIT_SECONDS set CLEARREAD_KEYCLOAK_WAIT_SECONDS=240
if not defined CLEARREAD_DEP_WAIT_SECONDS set CLEARREAD_DEP_WAIT_SECONDS=90

set COMPOSE_ENVFILE=
if exist "%ROOT%\.env" set COMPOSE_ENVFILE=--env-file "%ROOT%\.env"

where docker >nul 2>&1
if errorlevel 1 (
  echo ERROR: Docker CLI not found. Install Docker Desktop and retry.
  echo   Diagnose: scripts\doctor.bat
  exit /b 1
)
docker info >nul 2>&1
if errorlevel 1 (
  echo ERROR: Docker is not running. Start Docker Desktop, then retry.
  echo   Diagnose: scripts\doctor.bat
  exit /b 1
)

call :require_port_free %CLEARREAD_DB_PORT% Postgres CLEARREAD_DB_PORT 15432
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_S3_PORT% S3/LocalStack CLEARREAD_S3_PORT 19000
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_KEYCLOAK_PORT% Keycloak CLEARREAD_KEYCLOAK_PORT 18081
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_KEYCLOAK_MGMT_PORT% Keycloak-mgmt CLEARREAD_KEYCLOAK_MGMT_PORT 19002
if errorlevel 1 exit /b 1

echo %PROJECT%> "%ROOT%\.clearread-run\compose.project"
echo %COMPOSE_FILE%> "%ROOT%\.clearread-run\compose.file"

echo Starting ClearRead deps ^(db -^> LocalStack/minio -^> Keycloak^)...
echo   db=:%CLEARREAD_DB_PORT%  s3=:%CLEARREAD_S3_PORT%  keycloak=:%CLEARREAD_KEYCLOAK_PORT%  kc-mgmt=:%CLEARREAD_KEYCLOAK_MGMT_PORT%

call :start_one db
if errorlevel 1 exit /b 1
call :wait_healthy db %CLEARREAD_DEP_WAIT_SECONDS%
if errorlevel 1 exit /b 1

call :start_one minio
if errorlevel 1 exit /b 1
call :wait_healthy minio %CLEARREAD_DEP_WAIT_SECONDS%
if errorlevel 1 exit /b 1

call :start_one keycloak
if errorlevel 1 exit /b 1

echo.
echo Waiting for Keycloak OIDC ^(http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread^)...
echo   First boot can take 1-3 minutes ^(Docker Desktop^); warm starts are usually under 30s.
echo   Timeout: %CLEARREAD_KEYCLOAK_WAIT_SECONDS%s
set /a kc_elapsed=0
:wait_kc
curl -sf "http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread/.well-known/openid-configuration" >nul 2>&1
if not errorlevel 1 (
  echo Keycloak is ready ^(%kc_elapsed%s^).
  goto kc_ready
)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a keycloak 2>nul | findstr /I "Exit Dead" >nul
if not errorlevel 1 (
  echo ERROR: Keycloak container exited.
  call :fail_service keycloak
  exit /b 1
)
set /a rem=%kc_elapsed% %% 15
if %rem%==0 (
  set /a kc_left=%CLEARREAD_KEYCLOAK_WAIT_SECONDS%-%kc_elapsed%
  echo   ... Keycloak still starting ^(%kc_elapsed%s elapsed, !kc_left!s left^)
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps keycloak
)
set /a kc_elapsed+=5
if %kc_elapsed% GEQ %CLEARREAD_KEYCLOAK_WAIT_SECONDS% goto kc_failed
timeout /t 5 /nobreak >nul
goto wait_kc

:kc_failed
echo ERROR: Keycloak did not become ready within %CLEARREAD_KEYCLOAK_WAIT_SECONDS%s.
call :fail_service keycloak
exit /b 1

:kc_ready
echo.
echo === Final dependency status ===
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a db minio keycloak
echo.
echo Deps started ^(db=:%CLEARREAD_DB_PORT% s3/LocalStack=:%CLEARREAD_S3_PORT% keycloak=:%CLEARREAD_KEYCLOAK_PORT%^).
echo   Note: Compose service name for object storage is still 'minio' ^(LocalStack image^).
echo API/web: scripts\start.bat   Diagnose: scripts\doctor.bat
endlocal
exit /b 0

:start_one
set SVC=%~1
echo.
echo === Starting %SVC% ===
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% up -d %SVC%
if errorlevel 1 (
  echo ERROR: docker compose up failed for %SVC%.
  call :fail_service %SVC%
  exit /b 1
)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a %SVC%
exit /b 0

:wait_healthy
set SVC=%~1
set TIMEOUT=%~2
if "%TIMEOUT%"=="" set TIMEOUT=90
set /a elapsed=0
echo Waiting for %SVC% healthy ^(timeout %TIMEOUT%s^)...
:wh_loop
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps %SVC% 2>nul | findstr /I "healthy" >nul
if not errorlevel 1 (
  echo   %SVC% is healthy ^(%elapsed%s^).
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps %SVC%
  exit /b 0
)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a %SVC% 2>nul | findstr /I "Exit Dead" >nul
if not errorlevel 1 (
  echo   %SVC% container exited.
  call :fail_service %SVC%
  exit /b 1
)
set /a rem=%elapsed% %% 15
if %rem%==0 (
  echo   ... %SVC% still starting ^(%elapsed%s / %TIMEOUT%s^)
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a %SVC%
)
set /a elapsed+=5
if %elapsed% GEQ %TIMEOUT% (
  call :fail_service %SVC%
  exit /b 1
)
timeout /t 5 /nobreak >nul
goto wh_loop

:fail_service
set SVC=%~1
echo ERROR: Dependency '%SVC%' failed to become ready.
echo --- docker compose ps ---
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a
echo --- docker logs --tail 80 %SVC% ---
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% logs --tail=80 %SVC%
echo   Diagnose: scripts\doctor.bat
echo   Docs: docs\ops\compose-deps-fix.md
exit /b 1

:require_port_free
set PORT=%~1
set NAME=%~2
set ENVVAR=%~3
set SUGGEST=%~4
netstat -ano | findstr ":%PORT% " | findstr /I "LISTENING" >nul
if errorlevel 1 exit /b 0
docker ps --format "{{.Names}} {{.Ports}}" 2>nul | findstr /I "%PROJECT%-" | findstr /C:"%PORT%->" >nul
if not errorlevel 1 (
  echo Port %PORT% ^(%NAME%^) already published by ClearRead Compose — will re-use.
  exit /b 0
)
echo ERROR: Port %PORT% is in use. Stop the other process or set %ENVVAR%=%SUGGEST%
echo   Find PID: netstat -ano ^| findstr :%PORT%
echo   Or PowerShell: Get-NetTCPConnection -LocalPort %PORT% ^| Select OwningProcess
echo   Diagnose: scripts\doctor.bat
echo   Override example: echo %ENVVAR%=%SUGGEST%^>^>.env ^& scripts\start-deps.bat
exit /b 1
