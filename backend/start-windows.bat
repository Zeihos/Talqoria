@echo off
cd /d "%~dp0"
where node >nul 2>&1
if errorlevel 1 (
    echo Node.js 20 or newer is required.
    pause
    exit /b 1
)
echo ======================================
echo   TALQORIA Site + API v0.16
echo ======================================
echo Website: http://127.0.0.1:8080
if not defined TALQORIA_BRIDGE_TOKEN echo NOTE: status bridge disabled; no bridge token configured.
node server.js
pause
