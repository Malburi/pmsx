@echo off
rem PMS-X start (Windows). Uses bundled JRE, profile=prod, config\application-prod.yml
setlocal
for %%I in ("%~dp0..") do set "PMSX_HOME=%%~fI"
cd /d "%PMSX_HOME%"
if not exist "run" mkdir "run"
if not exist "logs" mkdir "logs"
if not exist "run\pmsx.pid" goto launch
set /p OLDPID=<"run\pmsx.pid"
tasklist /FI "PID eq %OLDPID%" 2>nul | find "%OLDPID%" >nul
if not errorlevel 1 (
  echo PMS-X is already running. PID=%OLDPID%
  exit /b 1
)
del "run\pmsx.pid" >nul 2>&1

:launch
set "JAVA=%PMSX_HOME%\jre\bin\java.exe"
if not exist "%JAVA%" set "JAVA=java"
if "%JAVA_OPTS%"=="" set "JAVA_OPTS=-Xms512m -Xmx2g"
echo Starting PMS-X (home=%PMSX_HOME%) ...
start "PMS-X" /B "%JAVA%" %JAVA_OPTS% -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dloader.path="%PMSX_HOME%\lib" -jar "%PMSX_HOME%\pmsx-server.jar" --spring.profiles.active=prod --spring.config.additional-location=optional:file:./config/ 1>>"logs\console.log" 2>&1
echo Started. Log: %PMSX_HOME%\logs\pmsx.log  (PID file: run\pmsx.pid)
endlocal
