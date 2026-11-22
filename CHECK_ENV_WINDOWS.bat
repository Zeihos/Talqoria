@echo off
setlocal EnableExtensions
cd /d "%~dp0"
echo ===========================================
echo TALQORIA v0.18 - BUILD ENVIRONMENT CHECK
 echo ===========================================
where javac >nul 2>&1
if errorlevel 1 (echo [FAIL] JDK not found. Install JDK 21 or newer. & goto :fail)
javac -version 2>&1
set "LOCAL_MAVEN=%~dp0.tools\apache-maven-3.9.16\bin\mvn.cmd"
if exist "%LOCAL_MAVEN%" (
  echo [OK] Local Maven:
  call "%LOCAL_MAVEN%" -version
) else (
  where mvn >nul 2>&1
  if errorlevel 1 (
    echo [INFO] Maven not installed yet. BUILD_PLUGIN_WINDOWS.bat will download a verified local copy.
  ) else (
    call mvn -version
  )
)
where node >nul 2>&1
if errorlevel 1 (echo [FAIL] Node.js not found. Install Node.js 20+. & goto :fail)
node --version
if not exist "server\paper.jar" (echo [NOTICE] Put a compatible Paper server JAR in server\paper.jar.)
if not exist "server\plugins\TalqoriaCore.jar" (echo [NOTICE] Plugin is not built yet. Run BUILD_PLUGIN_WINDOWS.bat.)
echo [OK] Environment checked. Maven and Paper runtime still need a real build test.
pause
exit /b 0
:fail
pause
exit /b 1
