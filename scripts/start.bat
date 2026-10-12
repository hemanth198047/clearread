@echo off
setlocal
set ROOT=%~dp0..
cd /d "%ROOT%"
set PROJECT=clearread
set COMPOSE_FILE=%ROOT%\infra\compose.yaml
if not exist "%ROOT%\.clearread-run" mkdir "%ROOT%\.clearread-run"

echo Building ClearRead images...
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" build api web
if errorlevel 1 (
  echo ERROR: docker compose build failed.
  echo If api failed with exit 127 on ./gradlew, ensure backend\gradlew has LF line endings
  echo and gradle\wrapper\gradle-wrapper.jar is present. See docs\ops\compose-build-fix.md
  echo Prefer Git Bash or WSL: bash scripts/start.sh
  exit /b 1
)

echo Starting ClearRead Compose stack...
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" up -d db minio keycloak
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" up -d api web
echo %PROJECT%> "%ROOT%\.clearread-run\compose.project"
echo %COMPOSE_FILE%> "%ROOT%\.clearread-run\compose.file"

echo Waiting for API health...
set /a n=0
:wait
curl -sf http://localhost:8080/v1/health >nul 2>&1
if not errorlevel 1 goto ready
set /a n+=1
if %n%==15 (
  echo Still waiting — dumping api status...
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" ps api
)
if %n%==45 (
  echo Still waiting — recent api logs:
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" logs --tail=40 api
)
if %n% GEQ 90 (
  echo.
  echo API did not become healthy after ~3 minutes.
  echo This Windows script does not run the host API/Vite fallback.
  echo If container-to-container networking is broken, use Git Bash or WSL:
  echo   bash scripts/start.sh
  echo That script falls back to host bootRun + Vite against published ports.
  echo Logs:
  docker compose -p %PROJECT% -f "%COMPOSE_FILE%" logs --tail=80 api
  exit /b 1
)
timeout /t 2 /nobreak >nul
goto wait

:ready
echo.
echo ClearRead started.
echo   Web:      http://localhost:5173
echo   API:      http://localhost:8080
echo   Keycloak: http://localhost:8081  (admin/admin)
echo   Demo user: demo / demo
echo Smoke: scripts\check.bat
echo Stop with: scripts\stop.bat
endlocal
