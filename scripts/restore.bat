@echo off
REM ClearRead Postgres restore for Windows (Compose db). Prefer Git Bash: bash scripts/restore.sh
setlocal
set ROOT=%~dp0..
cd /d "%ROOT%"
set PROJECT=clearread
set COMPOSE_FILE=%ROOT%\infra\compose.yaml

if "%~1"=="" (
  echo Usage: restore.bat path\to\backup-dir-or-postgres.dump
  echo Example: restore.bat .clearread-run\backups\some-dir
  exit /b 1
)

set SRC=%~1
set DUMP=%SRC%
if exist "%SRC%\postgres.dump" set DUMP=%SRC%\postgres.dump
if not exist "%DUMP%" (
  echo Missing dump: %DUMP%
  exit /b 1
)

echo Restoring Postgres from %DUMP%
echo Note: pg_restore --clean may print warnings; exit code 1 can be ignorable.
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" exec -T db pg_restore -U clearread -d clearread --clean --if-exists --no-owner < "%DUMP%"
set RC=%ERRORLEVEL%
if %RC% GTR 1 (
  echo pg_restore failed with exit %RC%
  echo Ensure db is up (scripts\start.bat) then retry.
  exit /b %RC%
)

echo DB restore finished (exit %RC%).
echo Re-sync object-store tenants/* from the same backup window, then run scripts\check.bat
endlocal
