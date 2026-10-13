@echo off
setlocal EnableDelayedExpansion
REM Dev-only: repair Flyway checksums against local Compose Postgres.
REM Do NOT use against production. Prefer fixing migrations; never edit applied V*.

set ROOT=%~dp0..
cd /d "%ROOT%"
set PROJECT=clearread
if defined CLEARREAD_COMPOSE_PROJECT set PROJECT=%CLEARREAD_COMPOSE_PROJECT%
set COMPOSE_FILE=%ROOT%\infra\compose.yaml
set MIGRATIONS=%ROOT%\backend\src\main\resources\db\migration
set FLYWAY_IMAGE=flyway/flyway:10.22.0
if defined CLEARREAD_FLYWAY_IMAGE set FLYWAY_IMAGE=%CLEARREAD_FLYWAY_IMAGE%
set DB_CONTAINER=clearread-db-1
if defined CLEARREAD_DB_CONTAINER set DB_CONTAINER=%CLEARREAD_DB_CONTAINER%

if exist "%ROOT%\.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%ROOT%\.env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

if not defined CLEARREAD_DB_USER set CLEARREAD_DB_USER=clearread
if not defined CLEARREAD_DB_PASSWORD set CLEARREAD_DB_PASSWORD=clearread
if not defined CLEARREAD_DB_NAME set CLEARREAD_DB_NAME=clearread

where docker >nul 2>&1
if errorlevel 1 (
  echo ERROR: Docker CLI not found.
  exit /b 1
)
docker info >nul 2>&1
if errorlevel 1 (
  echo ERROR: Docker daemon not reachable. Start Docker Desktop first.
  exit /b 1
)
if not exist "%MIGRATIONS%" (
  echo ERROR: Migrations directory missing: %MIGRATIONS%
  exit /b 1
)

docker inspect "%DB_CONTAINER%" >nul 2>&1
if errorlevel 1 (
  echo ERROR: DB container '%DB_CONTAINER%' not found.
  echo   Start deps first: scripts\start-deps.bat
  echo   Or wipe local DB volume ^(dev only^):
  echo     docker compose -p %PROJECT% -f infra\compose.yaml stop api
  echo     docker volume rm clearread_clearread_pg
  exit /b 1
)

for /f "delims=" %%S in ('docker inspect -f "{{.State.Status}}" "%DB_CONTAINER%" 2^>nul') do set STATUS=%%S
if /i not "%STATUS%"=="running" (
  echo ERROR: DB container '%DB_CONTAINER%' is not running ^(status=%STATUS%^).
  echo   Try: docker start %DB_CONTAINER%
  exit /b 1
)

for /f "delims=" %%N in ('docker inspect -f "{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}" "%DB_CONTAINER%" 2^>nul') do set NETWORK=%%N
if "%NETWORK%"=="" (
  echo ERROR: Could not resolve Compose network for %DB_CONTAINER%
  exit /b 1
)

echo === ClearRead Flyway repair ^(dev only^) ===
echo Project:   %PROJECT%
echo DB:        %DB_CONTAINER%
echo Network:   %NETWORK%
echo SQL:       %MIGRATIONS%
echo Image:     %FLYWAY_IMAGE%
echo.
echo WARNING: This updates flyway_schema_history checksums to match local SQL.
echo          Do not use against production databases.
echo.

docker run --rm --network "%NETWORK%" -v "%MIGRATIONS%:/flyway/sql:ro" %FLYWAY_IMAGE% -url=jdbc:postgresql://db:5432/%CLEARREAD_DB_NAME% -user=%CLEARREAD_DB_USER% -password=%CLEARREAD_DB_PASSWORD% -locations=filesystem:/flyway/sql repair
if errorlevel 1 (
  echo ERROR: Flyway repair failed.
  exit /b 1
)

echo.
echo OK  Flyway repair finished.
echo Next: restart API ^(start.bat or docker compose -p %PROJECT% -f infra\compose.yaml up -d api^)
echo.
echo Nuclear alternative ^(dev wipe Postgres volume — destroys local data^):
echo   docker compose -p %PROJECT% -f infra\compose.yaml stop api
echo   docker volume rm clearread_clearread_pg
echo   scripts\start-deps.bat
echo   start.bat
exit /b 0
