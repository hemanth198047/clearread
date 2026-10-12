@echo off
REM Root convenience wrapper — forwards to scripts\doctor.bat
call "%~dp0scripts\doctor.bat" %*
exit /b %ERRORLEVEL%
