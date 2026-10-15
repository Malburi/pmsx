@echo off
rem PMS-X stop (Windows)
setlocal
for %%I in ("%~dp0..") do set "PMSX_HOME=%%~fI"
if not exist "%PMSX_HOME%\run\pmsx.pid" (
  echo PMS-X is not running (no run\pmsx.pid^).
  exit /b 0
)
set /p PID=<"%PMSX_HOME%\run\pmsx.pid"
echo Stopping PMS-X PID=%PID% ...
taskkill /PID %PID% /T /F >nul 2>&1
del "%PMSX_HOME%\run\pmsx.pid" >nul 2>&1
echo Stopped.
endlocal
