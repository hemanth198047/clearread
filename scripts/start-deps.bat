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
if not defined CLEARREAD_COMPOSE_UP_TIMEOUT set CLEARREAD_COMPOSE_UP_TIMEOUT=90
if not defined CLEARREAD_CREATED_STUCK_SECONDS set CLEARREAD_CREATED_STUCK_SECONDS=45

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
echo   Timeouts: compose-up=%CLEARREAD_COMPOSE_UP_TIMEOUT%s  created-stuck=%CLEARREAD_CREATED_STUCK_SECONDS%s  dep-healthy=%CLEARREAD_DEP_WAIT_SECONDS%s

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
call :svc_is_created keycloak
if not errorlevel 1 (
  if %kc_elapsed% GEQ %CLEARREAD_CREATED_STUCK_SECONDS% (
    call :fail_created_stuck keycloak
    exit /b 1
  )
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
echo   compose up timeout=%CLEARREAD_COMPOSE_UP_TIMEOUT%s  Created-stuck threshold=%CLEARREAD_CREATED_STUCK_SECONDS%s
call :compose_up_timed %SVC%
set UP_RC=!ERRORLEVEL!
if !UP_RC! EQU 124 (
  echo ERROR: docker compose up -d %SVC% timed out after %CLEARREAD_COMPOSE_UP_TIMEOUT%s.
  echo   Often means Docker Desktop is wedged on docker start.
  call :dump_inspect %SVC%
  call :svc_is_created %SVC%
  if not errorlevel 1 (
    call :fail_created_stuck %SVC%
    exit /b 1
  )
  call :print_created_stuck_recovery
  exit /b 1
)
if not !UP_RC! EQU 0 (
  echo ERROR: docker compose up failed for %SVC% ^(exit !UP_RC!^).
  call :fail_service %SVC%
  exit /b 1
)
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a %SVC%
call :wait_left_created %SVC%
if errorlevel 1 exit /b 1
exit /b 0

:compose_up_timed
REM Timed docker compose up -d via PowerShell WaitForExit (Windows has no GNU timeout).
set SVC=%~1
set PS1=%TEMP%\clearread-compose-up-%RANDOM%.ps1
set OUT=%TEMP%\clearread-compose-up-%RANDOM%.out
(
  echo $ErrorActionPreference = 'Continue'
  echo $svc = '%SVC%'
  echo $timeoutSec = %CLEARREAD_COMPOSE_UP_TIMEOUT%
  echo $outFile = '%OUT%'
  echo $args = New-Object System.Collections.Generic.List[string]
  echo [void]$args.Add^('compose'^)
  echo [void]$args.Add^('-p'^)
  echo [void]$args.Add^('%PROJECT%'^)
  echo [void]$args.Add^('-f'^)
  echo [void]$args.Add^('%COMPOSE_FILE%'^)
  if exist "%ROOT%\.env" (
    echo [void]$args.Add^('--env-file'^)
    echo [void]$args.Add^('%ROOT%\.env'^)
  )
  echo [void]$args.Add^('up'^)
  echo [void]$args.Add^('-d'^)
  echo [void]$args.Add^($svc^)
  echo $p = Start-Process -FilePath 'docker' -ArgumentList $args.ToArray^(^) -NoNewWindow -PassThru -RedirectStandardOutput $outFile -RedirectStandardError $outFile
  echo if ^(-not $p.WaitForExit^($timeoutSec * 1000^)^) {
  echo   try { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } catch {}
  echo   'TIMEOUT' ^| Out-File -FilePath $outFile -Append -Encoding utf8
  echo   exit 124
  echo }
  echo exit $p.ExitCode
) > "%PS1%"
powershell -NoProfile -ExecutionPolicy Bypass -File "%PS1%"
set UP_RC=%ERRORLEVEL%
if exist "%OUT%" type "%OUT%"
del /q "%PS1%" >nul 2>&1
del /q "%OUT%" >nul 2>&1
exit /b %UP_RC%

:wait_left_created
set SVC=%~1
set /a elapsed=0
echo Waiting for %SVC% to leave Created ^(max %CLEARREAD_CREATED_STUCK_SECONDS%s^)...
:wlc_loop
call :svc_is_created %SVC%
if errorlevel 1 (
  echo   %SVC% left Created ^(%elapsed%s^).
  exit /b 0
)
set /a rem=%elapsed% %% 10
if %rem%==0 echo   ... %SVC% still Created ^(%elapsed%s / %CLEARREAD_CREATED_STUCK_SECONDS%s^)
set /a elapsed+=5
if %elapsed% GEQ %CLEARREAD_CREATED_STUCK_SECONDS% (
  call :fail_created_stuck %SVC%
  exit /b 1
)
timeout /t 5 /nobreak >nul
goto wlc_loop

:wait_healthy
set SVC=%~1
set TIMEOUT=%~2
if "%TIMEOUT%"=="" set TIMEOUT=90
set /a elapsed=0
echo Waiting for %SVC% healthy ^(timeout %TIMEOUT%s^)...
:wh_loop
call :svc_is_created %SVC%
if not errorlevel 1 (
  if %elapsed% GEQ %CLEARREAD_CREATED_STUCK_SECONDS% (
    call :fail_created_stuck %SVC%
    exit /b 1
  )
)
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

:svc_is_created
REM exit 0 if Created, 1 otherwise
set SVC=%~1
for /f "tokens=1,2,3 delims=|" %%A in ('docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a --format "{{.Service}}|{{.State}}|{{.Status}}" 2^>nul ^| findstr /B /I /C:"%SVC%|"') do (
  echo %%B %%C | findstr /I "Created" >nul
  if not errorlevel 1 exit /b 0
  exit /b 1
)
exit /b 1

:dump_inspect
set SVC=%~1
set CNAME=
for /f "tokens=1,2 delims=|" %%A in ('docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a --format "{{.Service}}|{{.Name}}" 2^>nul ^| findstr /B /I /C:"%SVC%|"') do set CNAME=%%B
if "!CNAME!"=="" exit /b 0
echo --- docker inspect State ^(!CNAME!^) ---
docker inspect --format "{{json .State}}" !CNAME! 2>nul
echo.
exit /b 0

:fail_created_stuck
set SVC=%~1
echo ERROR: '%SVC%' stuck in STATUS=Created — Docker start never completed.
call :dump_inspect %SVC%
call :print_created_stuck_recovery
echo   Diagnose: scripts\doctor.bat
echo   Docs: docs\ops\compose-deps-fix.md#containers-stuck-in-created
exit /b 1

:print_created_stuck_recovery
echo.
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo DIAGNOSIS: Containers stuck in Created — Docker Desktop / WSL is wedged
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo   docker compose up / docker start did not leave Created.
echo   App Compose cannot work around a wedged Docker engine.
echo.
echo   If `docker start clearread-db-1` itself hangs, ONLY this fixes it:
echo.
echo   1. Quit Docker Desktop completely
echo   2. wsl --shutdown
echo   3. Start Docker Desktop — wait until green / Running
echo   4. docker start clearread-db-1   ^(if this hangs, repeat 1-3^)
echo   5. scripts\doctor.bat ^&^& scripts\start-deps.bat
echo.
echo   Docs: docs\ops\compose-deps-fix.md#containers-stuck-in-created
echo !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
echo.
exit /b 0

:fail_service
set SVC=%~1
call :svc_is_created %SVC%
if not errorlevel 1 (
  call :fail_created_stuck %SVC%
  exit /b 1
)
echo ERROR: Dependency '%SVC%' failed to become ready.
echo --- docker compose ps ---
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% ps -a
echo --- docker logs --tail 80 %SVC% ---
docker compose -p %PROJECT% -f "%COMPOSE_FILE%" %COMPOSE_ENVFILE% logs --tail=80 %SVC%
call :dump_inspect %SVC%
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
