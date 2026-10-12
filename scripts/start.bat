@echo off
setlocal EnableDelayedExpansion
set ROOT=%~dp0..
cd /d "%ROOT%"
set PROJECT=clearread
set COMPOSE_FILE=%ROOT%\infra\compose.yaml
if not exist "%ROOT%\.clearread-run" mkdir "%ROOT%\.clearread-run"

REM Load repo-root .env (KEY=VALUE lines; comments and blanks ignored)
if exist "%ROOT%\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%\.env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

if not defined CLEARREAD_API_PORT set CLEARREAD_API_PORT=8080
if not defined CLEARREAD_WEB_PORT set CLEARREAD_WEB_PORT=5173
if not defined CLEARREAD_KEYCLOAK_PORT set CLEARREAD_KEYCLOAK_PORT=8081
if not defined CLEARREAD_KEYCLOAK_MGMT_PORT set CLEARREAD_KEYCLOAK_MGMT_PORT=9002
REM Default 5433 avoids Windows local Postgres already bound on 5432.
if not defined CLEARREAD_DB_PORT set CLEARREAD_DB_PORT=5433
if not defined CLEARREAD_S3_PORT set CLEARREAD_S3_PORT=9000
if not defined VITE_API_BASE_URL set VITE_API_BASE_URL=http://localhost:%CLEARREAD_API_PORT%
if not defined VITE_OIDC_AUTHORITY set VITE_OIDC_AUTHORITY=http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread
if not defined CLEARREAD_OIDC_ISSUER set CLEARREAD_OIDC_ISSUER=http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread
if not defined CLEARREAD_CORS_ORIGINS set CLEARREAD_CORS_ORIGINS=http://localhost:%CLEARREAD_WEB_PORT%,http://localhost:3000
if not defined CLEARREAD_API_ORIGIN set CLEARREAD_API_ORIGIN=http://localhost:%CLEARREAD_API_PORT%
if not defined CLEARREAD_OIDC_ORIGIN set CLEARREAD_OIDC_ORIGIN=http://localhost:%CLEARREAD_KEYCLOAK_PORT%
if not defined CLEARREAD_HEALTH_ATTEMPTS set CLEARREAD_HEALTH_ATTEMPTS=45
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

call :require_port_free %CLEARREAD_API_PORT% API CLEARREAD_API_PORT
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_WEB_PORT% Web CLEARREAD_WEB_PORT
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_KEYCLOAK_PORT% Keycloak CLEARREAD_KEYCLOAK_PORT
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_KEYCLOAK_MGMT_PORT% Keycloak-mgmt CLEARREAD_KEYCLOAK_MGMT_PORT
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_DB_PORT% Postgres CLEARREAD_DB_PORT
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_S3_PORT% S3/LocalStack CLEARREAD_S3_PORT
if errorlevel 1 exit /b 1

echo Building ClearRead images...
echo   API host port=%CLEARREAD_API_PORT%  Web host port=%CLEARREAD_WEB_PORT%  VITE_API_BASE_URL=%VITE_API_BASE_URL%
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% build api web
if errorlevel 1 (
  echo ERROR: docker compose build failed.
  echo.
  echo libnative-platform.so FAILED TO LOAD ^(Could not initialize native services^):
  echo   Stale/corrupt BuildKit Gradle cache on Docker Desktop — not a zip timeout.
  echo   git pull
  echo   docker builder prune -f
  echo   docker compose -p %PROJECT% -f "%COMPOSE_FILE%" build --no-cache api
  echo.
  echo Gradle DOWNLOAD TIMEOUT ^(SocketTimeoutException / timeout on gradle-*-bin.zip^):
  echo   This is NOT exit 127. Retry after: docker pull gradle:8.14.3-jdk21
  echo   Then: docker compose -p %PROJECT% -f "%COMPOSE_FILE%" build api
  echo   Or host JAR: cd backend ^&^& gradlew.bat bootJar --no-daemon
  echo   Or Git Bash hybrid: bash scripts/start.sh
  echo.
  echo Exit 127 on ./gradlew ^(separate issue^): missing +x / CRLF line endings.
  echo   Ensure backend\gradlew is LF and gradle\wrapper\gradle-wrapper.jar is present.
  echo See docs\ops\compose-build-fix.md
  exit /b 1
)

echo Starting ClearRead Compose deps ^(db -^> LocalStack/minio -^> Keycloak^)...
call :start_dep db
if errorlevel 1 exit /b 1
call :wait_dep_healthy db %CLEARREAD_DEP_WAIT_SECONDS%
if errorlevel 1 exit /b 1
call :start_dep minio
if errorlevel 1 exit /b 1
call :wait_dep_healthy minio %CLEARREAD_DEP_WAIT_SECONDS%
if errorlevel 1 exit /b 1
call :start_dep keycloak
if errorlevel 1 exit /b 1
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a db minio keycloak

echo Waiting for Keycloak ^(http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread^)...
echo   First boot can take 1-3 minutes ^(Docker Desktop^); warm starts are usually under 30s.
echo   Timeout: %CLEARREAD_KEYCLOAK_WAIT_SECONDS%s ^(override CLEARREAD_KEYCLOAK_WAIT_SECONDS^).
set /a kc_elapsed=0
:wait_kc
curl -sf "http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread/.well-known/openid-configuration" >nul 2>&1
if not errorlevel 1 (
  echo Keycloak is ready ^(%kc_elapsed%s^).
  goto kc_ready
)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a keycloak 2>nul | findstr /I "Exit Dead" >nul
if not errorlevel 1 (
  echo ERROR: Keycloak container is not running — stopping wait early.
  call :fail_dep keycloak
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
echo.
echo ERROR: Keycloak did not become ready within %CLEARREAD_KEYCLOAK_WAIT_SECONDS%s.
echo   Inspect: docker logs clearread-keycloak-1
echo   Recreate: docker compose -p %PROJECT% -f "%COMPOSE_FILE%" up -d --force-recreate keycloak
echo   Wipe realm volume after realm JSON changes:
echo     docker compose -p %PROJECT% -f "%COMPOSE_FILE%" stop keycloak
echo     docker volume rm %PROJECT%_clearread_keycloak
echo   Give Docker Desktop more RAM ^(4GB+ recommended^).
echo   See docs\ops\keycloak-startup.md
call :fail_dep keycloak
exit /b 1

:kc_ready
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% up -d api web >"%ROOT%\.clearread-run\compose-up.log" 2>&1
if errorlevel 1 (
  type "%ROOT%\.clearread-run\compose-up.log"
  findstr /I /C:"port is already allocated" /C:"address already in use" /C:"bind" "%ROOT%\.clearread-run\compose-up.log" >nul
  if not errorlevel 1 (
    echo ERROR: Compose could not bind a published port.
    echo   Port conflict — stop the other process or set CLEARREAD_API_PORT / CLEARREAD_WEB_PORT in .env
    echo   Find PID: netstat -ano ^| findstr :%CLEARREAD_API_PORT%
    echo   Or PowerShell: Get-NetTCPConnection -LocalPort %CLEARREAD_API_PORT% ^| Select OwningProcess
    echo   Free ClearRead leftovers: scripts\stop.bat
  ) else (
    echo ERROR: docker compose up failed for api/web. See log above.
  )
  echo   Diagnose: scripts\doctor.bat
  exit /b 1
)
echo %PROJECT%> "%ROOT%\.clearread-run\compose.project"
echo %COMPOSE_FILE%> "%ROOT%\.clearread-run\compose.file"

echo Waiting for API health (http://localhost:%CLEARREAD_API_PORT%/v1/health)...
set /a n=0
:wait
curl -sf http://localhost:%CLEARREAD_API_PORT%/v1/health >nul 2>&1
if not errorlevel 1 goto ready

REM Fail fast if api container exited / never started
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a api 2>nul | findstr /I "Exit Dead" >nul
if not errorlevel 1 (
  echo API container is not running — stopping health wait early.
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a api
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% logs --tail=40 api
  goto unhealthy
)

set /a n+=1
if %n%==10 (
  echo Still waiting — dumping api status...
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps api
)
if %n%==25 (
  echo Still waiting — recent api logs:
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% logs --tail=40 api
)
if %n% GEQ %CLEARREAD_HEALTH_ATTEMPTS% goto unhealthy
timeout /t 2 /nobreak >nul
goto wait

:unhealthy
echo.
echo ERROR: API did not become healthy after health wait.
echo This Windows script does not run the host API/Vite fallback.
echo If container-to-container networking is broken, use Git Bash or WSL:
echo   bash scripts/start.sh
echo That script falls back to host bootRun + Vite against published ports.
echo If this was a port conflict: set CLEARREAD_API_PORT in .env ^(see .env.example^).
echo Find PID: netstat -ano ^| findstr :%CLEARREAD_API_PORT%
echo Or PowerShell: Get-NetTCPConnection -LocalPort %CLEARREAD_API_PORT% ^| Select OwningProcess
echo Logs:
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% logs --tail=80 api
exit /b 1

:ready
echo.
echo ClearRead started.
echo   Web:      http://localhost:%CLEARREAD_WEB_PORT%
echo   API:      http://localhost:%CLEARREAD_API_PORT%
echo   Keycloak: http://localhost:%CLEARREAD_KEYCLOAK_PORT%  (console admin/admin on master)
echo   Demo accounts (ClearRead realm):
echo     admin / admin  — workspace admin
echo     user  / user   — standard member (author)
echo     demo  / demo   — legacy admin (compat)
echo   Sign up: open Web -^> Create account (Keycloak self-registration)
echo Smoke: scripts\check.bat
echo Diagnose: scripts\doctor.bat
echo Stop with: scripts\stop.bat
endlocal
exit /b 0

:start_dep
set SVC=%~1
echo.
echo === Starting %SVC% ===
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% up -d %SVC%
if errorlevel 1 (
  echo ERROR: docker compose up failed for %SVC%.
  call :fail_dep %SVC%
  exit /b 1
)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a %SVC%
exit /b 0

:wait_dep_healthy
set SVC=%~1
set TIMEOUT=%~2
if "%TIMEOUT%"=="" set TIMEOUT=90
set /a elapsed=0
echo Waiting for %SVC% healthy ^(timeout %TIMEOUT%s^)...
:wdh_loop
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps %SVC% 2>nul | findstr /I "healthy" >nul
if not errorlevel 1 (
  echo   %SVC% is healthy ^(%elapsed%s^).
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps %SVC%
  exit /b 0
)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a %SVC% 2>nul | findstr /I "Exit Dead" >nul
if not errorlevel 1 (
  echo   %SVC% container exited.
  call :fail_dep %SVC%
  exit /b 1
)
set /a rem=%elapsed% %% 15
if %rem%==0 (
  echo   ... %SVC% still starting ^(%elapsed%s / %TIMEOUT%s^)
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a %SVC%
)
set /a elapsed+=5
if %elapsed% GEQ %TIMEOUT% (
  call :fail_dep %SVC%
  exit /b 1
)
timeout /t 5 /nobreak >nul
goto wdh_loop

:fail_dep
set SVC=%~1
echo ERROR: Dependency '%SVC%' failed.
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
set SUGGEST=18080
if /I "%ENVVAR%"=="CLEARREAD_WEB_PORT" set SUGGEST=5174
if /I "%ENVVAR%"=="CLEARREAD_KEYCLOAK_PORT" set SUGGEST=18081
if /I "%ENVVAR%"=="CLEARREAD_KEYCLOAK_MGMT_PORT" set SUGGEST=19002
if /I "%ENVVAR%"=="CLEARREAD_DB_PORT" set SUGGEST=15432
if /I "%ENVVAR%"=="CLEARREAD_S3_PORT" set SUGGEST=19000
if /I "%ENVVAR%"=="CLEARREAD_API_PORT" set SUGGEST=18080
netstat -ano | findstr ":%PORT% " | findstr /I "LISTENING" >nul
if errorlevel 1 exit /b 0
REM Allow re-use when ClearRead Compose already publishes this port
docker ps --format "{{.Names}} {{.Ports}}" 2>nul | findstr /I "%PROJECT%-" | findstr /C:"%PORT%->" >nul
if not errorlevel 1 (
  echo Port %PORT% ^(%NAME%^) already published by ClearRead Compose — will re-use.
  exit /b 0
)
echo ERROR: Port %PORT% is in use. Stop the other process or set %ENVVAR%=%SUGGEST%
echo   Find PID: netstat -ano ^| findstr :%PORT%
echo   Or PowerShell: Get-NetTCPConnection -LocalPort %PORT% ^| Select OwningProcess
echo   Free ClearRead leftovers: scripts\stop.bat
echo   Diagnose: scripts\doctor.bat
echo   Override example: echo %ENVVAR%=%SUGGEST%^>^>.env ^& scripts\start.bat
exit /b 1
