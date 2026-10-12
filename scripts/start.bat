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
if not defined CLEARREAD_DB_PORT set CLEARREAD_DB_PORT=5432
if not defined CLEARREAD_S3_PORT set CLEARREAD_S3_PORT=9000
if not defined VITE_API_BASE_URL set VITE_API_BASE_URL=http://localhost:%CLEARREAD_API_PORT%
if not defined VITE_OIDC_AUTHORITY set VITE_OIDC_AUTHORITY=http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread
if not defined CLEARREAD_OIDC_ISSUER set CLEARREAD_OIDC_ISSUER=http://localhost:%CLEARREAD_KEYCLOAK_PORT%/realms/clearread
if not defined CLEARREAD_CORS_ORIGINS set CLEARREAD_CORS_ORIGINS=http://localhost:%CLEARREAD_WEB_PORT%,http://localhost:3000
if not defined CLEARREAD_API_ORIGIN set CLEARREAD_API_ORIGIN=http://localhost:%CLEARREAD_API_PORT%
if not defined CLEARREAD_OIDC_ORIGIN set CLEARREAD_OIDC_ORIGIN=http://localhost:%CLEARREAD_KEYCLOAK_PORT%
if not defined CLEARREAD_HEALTH_ATTEMPTS set CLEARREAD_HEALTH_ATTEMPTS=45

set COMPOSE_ENVFILE=
if exist "%ROOT%\.env" set COMPOSE_ENVFILE=--env-file "%ROOT%\.env"

call :require_port_free %CLEARREAD_API_PORT% API CLEARREAD_API_PORT
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_WEB_PORT% Web CLEARREAD_WEB_PORT
if errorlevel 1 exit /b 1
call :require_port_free %CLEARREAD_KEYCLOAK_PORT% Keycloak CLEARREAD_KEYCLOAK_PORT
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
  echo If api failed with exit 127 on ./gradlew, ensure backend\gradlew has LF line endings
  echo and gradle\wrapper\gradle-wrapper.jar is present. See docs\ops\compose-build-fix.md
  echo Prefer Git Bash or WSL: bash scripts/start.sh
  exit /b 1
)

echo Starting ClearRead Compose stack...
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% up -d db minio keycloak
if errorlevel 1 (
  echo ERROR: Compose failed starting dependencies. Check ports and Docker Desktop.
  echo Override ports via .env — see .env.example
  exit /b 1
)
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
echo   Keycloak: http://localhost:%CLEARREAD_KEYCLOAK_PORT%  (admin/admin)
echo   Demo user: demo / demo
echo Smoke: scripts\check.bat
echo Stop with: scripts\stop.bat
endlocal
exit /b 0

:require_port_free
set PORT=%~1
set NAME=%~2
set ENVVAR=%~3
set SUGGEST=18080
if /I "%ENVVAR%"=="CLEARREAD_WEB_PORT" set SUGGEST=5174
if /I "%ENVVAR%"=="CLEARREAD_KEYCLOAK_PORT" set SUGGEST=18081
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
echo   Override example: echo %ENVVAR%=%SUGGEST%^>^>.env ^& scripts\start.bat
exit /b 1
