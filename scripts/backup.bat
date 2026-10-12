@echo off
REM ClearRead Postgres backup for Windows (Compose db). For full helper + MANIFEST, prefer Git Bash: bash scripts/backup.sh
setlocal
set ROOT=%~dp0..
cd /d "%ROOT%"
set PROJECT=clearread
set COMPOSE_FILE=%ROOT%\infra\compose.yaml

if "%~1"=="" (
  set OUT=%ROOT%\.clearread-run\backups\win-%DATE:~10,4%%DATE:~4,2%%DATE:~7,2%-%TIME:~0,2%%TIME:~3,2%%TIME:~6,2%
  set OUT=%OUT: =0%
) else (
  set OUT=%~1
)
if not exist "%OUT%" mkdir "%OUT%"

echo Backing up Postgres to %OUT%\postgres.dump
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" exec -T db pg_dump -U clearread -d clearread -Fc > "%OUT%\postgres.dump"
if errorlevel 1 (
  echo ERROR: pg_dump via Compose failed. Is the db container running? Try: scripts\start.bat
  echo Or use Git Bash/WSL: bash scripts/backup.sh
  exit /b 1
)

(
  echo ClearRead backup
  echo created_local=%DATE% %TIME%
  echo postgres=postgres.dump
  echo object_store=sync tenants/* prefixes from S3/MinIO/LocalStack separately
  echo restore=scripts\restore.bat or bash scripts/restore.sh — see docs/phase-6/operator-runbook.md
) > "%OUT%\MANIFEST.txt"

echo Backup complete: %OUT%
dir "%OUT%"
endlocal
