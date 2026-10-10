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
  echo and is present with gradle\wrapper\gradle-wrapper.jar. Prefer Git Bash/WSL.
  exit /b 1
)

echo Starting ClearRead Compose stack...
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" up -d
echo %PROJECT%> "%ROOT%\.clearread-run\compose.project"
echo %COMPOSE_FILE%> "%ROOT%\.clearread-run\compose.file"

echo Waiting for API health...
set /a n=0
:wait
curl -sf http://localhost:8080/v1/health >nul 2>&1
if not errorlevel 1 goto ready
set /a n+=1
if %n% GEQ 90 (
  echo API did not become healthy. Try: docker compose -p %PROJECT% -f "%COMPOSE_FILE%" logs api
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
echo Stop with: scripts\stop.bat
endlocal
