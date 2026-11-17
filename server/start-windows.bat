@echo off
setlocal
cd /d "%~dp0"
if not exist "paper.jar" (
  echo [ERROR] Put the compatible Paper JAR here as paper.jar.
  echo https://papermc.io/downloads/paper
  pause
  exit /b 1
)
where java >nul 2>&1
if errorlevel 1 (echo [ERROR] Java is not in PATH. & pause & exit /b 1)
java -Xms2G -Xmx4G -jar paper.jar --nogui
pause
