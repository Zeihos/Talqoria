@echo off
setlocal EnableExtensions
cd /d "%~dp0"
where node >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Node.js 20+ not found.
  pause
  exit /b 3
)
if "%~1"=="" (
  node "tools\preflight.js" "%~dp0"
) else (
  node "tools\preflight.js" "%~1"
)
set "EXIT_CODE=%errorlevel%"
echo.
if not "%EXIT_CODE%"=="0" echo [WARN] Preflight did not pass. Check the report above.
pause
exit /b %EXIT_CODE%
