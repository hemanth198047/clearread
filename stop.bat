@echo off
REM Root convenience wrapper — forwards to scripts\stop.bat
call "%~dp0scripts\stop.bat" %*
exit /b %ERRORLEVEL%
