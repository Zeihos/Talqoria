@echo off
setlocal
cd /d "%~dp0"
where node >nul 2>&1
if errorlevel 1 (
  echo ERROR: Node.js 20+ required.
  pause
  exit /b 2
)
if "%~1"=="" (
  node "tools\analyze-log.js" "server\logs\latest.log"
) else (
  node "tools\analyze-log.js" "%~1"
)
echo.
pause
