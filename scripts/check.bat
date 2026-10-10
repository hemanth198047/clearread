@echo off
setlocal
set FAIL=0

curl -sf http://localhost:8080/v1/health >nul || set /a FAIL+=1
curl -sf http://localhost:8080/v1/capabilities >nul || set /a FAIL+=1
curl -sf http://localhost:8080/v1/branding/default >nul || set /a FAIL+=1
curl -sf http://localhost:5173/ >nul || set /a FAIL+=1
curl -sf http://localhost:8081/realms/clearread/.well-known/openid-configuration >nul || set /a FAIL+=1

if %FAIL%==0 (
  echo All checks passed.
  exit /b 0
) else (
  echo %FAIL% check^(s^) failed.
  exit /b 1
)
