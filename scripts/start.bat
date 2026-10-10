@echo off
setlocal
set ROOT=%~dp0..
cd /d "%ROOT%"
set PROJECT=clearread
set COMPOSE_FILE=%ROOT%\infra\compose.yaml
if not exist "%ROOT%\.clearread-run" mkdir "%ROOT%\.clearread-run"

echo Starting ClearRead Compose stack...
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" up -d --build
echo %PROJECT%> "%ROOT%\.clearread-run\compose.project"
echo %COMPOSE_FILE%> "%ROOT%\.clearread-run\compose.file"

echo Waiting for API health...
:wait
curl -sf http://localhost:8080/v1/health >nul 2>&1
if errorlevel 1 (
  timeout /t 2 /nobreak >nul
  goto wait
)

echo.
echo ClearRead started.
echo   Web:      http://localhost:5173
echo   API:      http://localhost:8080
echo   Keycloak: http://localhost:8081  (admin/admin)
echo   Demo user: demo / demo
echo Stop with: scripts\stop.bat
endlocal
