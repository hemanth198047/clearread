@echo off
setlocal EnableDelayedExpansion
set ROOT=%~dp0..
set PROJECT=clearread
set COMPOSE_FILE=%ROOT%\infra\compose.yaml
if exist "%ROOT%\.clearread-run\compose.project" set /p PROJECT=<"%ROOT%\.clearread-run\compose.project"
if exist "%ROOT%\.clearread-run\compose.file" set /p COMPOSE_FILE=<"%ROOT%\.clearread-run\compose.file"

echo Stopping ClearRead Compose project '%PROJECT%' only...
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" down

REM Stop only ClearRead local helper processes tracked by pid files
for %%N in (api.pid web.pid) do (
  if exist "%ROOT%\.clearread-run\%%N" (
    set /p PID=<"%ROOT%\.clearread-run\%%N"
    if defined PID (
      tasklist /FI "PID eq !PID!" 2>nul | findstr /I "java.exe node.exe" >nul
      if not errorlevel 1 taskkill /PID !PID! /F >nul 2>&1
    )
    del "%ROOT%\.clearread-run\%%N" >nul 2>&1
  )
)

echo ClearRead stopped.
endlocal
