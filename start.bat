@echo off
REM Root convenience wrapper — forwards to scripts\start.bat
call "%~dp0scripts\start.bat" %*
exit /b %ERRORLEVEL%
