@echo off
setlocal
cd /d "%~dp0"
echo TALQORIA v0.17 OFFLINE BACKUP
 echo Stop Minecraft and confirm the game server is no longer running before this script.
choice /C YN /M "Is the server completely stopped"
if errorlevel 2 (echo Cancelled.& exit /b 1)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0BACKUP_OFFLINE_WINDOWS.ps1" -Stopped
if errorlevel 1 (echo Backup FAILED.& pause & exit /b 1)
echo Backup succeeded. Keep a copy outside this computer.
pause
