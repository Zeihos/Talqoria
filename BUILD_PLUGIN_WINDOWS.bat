@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"

echo ===========================================
echo TALQORIA v0.16 - BUILD TalqoriaCore
 echo ===========================================

where javac >nul 2>&1
if errorlevel 1 (
  echo [ERROR] JDK 21 or newer is required. Install JDK and reopen this window.
  pause
  exit /b 1
)
for /f "tokens=2" %%V in ('javac -version 2^>^&1') do set "JAVAC_VER=%%V"
for /f "tokens=1 delims=." %%V in ("!JAVAC_VER!") do set "JAVA_MAJOR=%%V"
if not defined JAVA_MAJOR (
  echo [ERROR] Cannot detect Java compiler version.
  pause
  exit /b 1
)
if !JAVA_MAJOR! LSS 21 (
  echo [ERROR] JDK 21+ required. Detected javac !JAVAC_VER!.
  pause
  exit /b 1
)

set "LOCAL_MAVEN=%~dp0.tools\apache-maven-3.9.16\bin\mvn.cmd"
set "MAVEN_CMD="
if exist "%LOCAL_MAVEN%" set "MAVEN_CMD=%LOCAL_MAVEN%"

if not defined MAVEN_CMD (
  for /f "tokens=3" %%V in ('mvn -version 2^>nul ^| findstr /B /C:"Apache Maven "') do set "GLOBAL_MAVEN_VER=%%V"
  if defined GLOBAL_MAVEN_VER (
    for /f "tokens=1,2 delims=." %%A in ("!GLOBAL_MAVEN_VER!") do (
      if %%A GTR 3 set "MAVEN_CMD=mvn.cmd"
      if %%A EQU 3 if %%B GEQ 9 set "MAVEN_CMD=mvn.cmd"
    )
  )
)

if not defined MAVEN_CMD (
  echo [INFO] Maven 3.9+ is not available. Installing local Maven 3.9.16.
  echo [INFO] Installation is inside TALQORIA\.tools. Administrator rights and PATH changes are NOT needed.
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\install-maven.ps1"
  if errorlevel 1 (
    echo [ERROR] Maven installation failed. Check your internet connection and the error above.
    pause
    exit /b 1
  )
  if not exist "%LOCAL_MAVEN%" (
    echo [ERROR] Local Maven executable is missing after installation.
    pause
    exit /b 1
  )
  set "MAVEN_CMD=%LOCAL_MAVEN%"
)

echo [INFO] Using Maven:
call "!MAVEN_CMD!" -version
if errorlevel 1 (
  echo [ERROR] Maven cannot run. Verify Java installation.
  pause
  exit /b 1
)

echo [INFO] Building TalqoriaCore with real Paper API dependencies...
call "!MAVEN_CMD!" -f "plugin\pom.xml" clean package
if errorlevel 1 (
  echo [ERROR] Maven build failed. Review output above; paper.jar is NOT built by this script.
  pause
  exit /b 1
)

if not exist "plugin\target\talqoria-core-0.16.0.jar" (
  echo [ERROR] Build returned success but the expected JAR is missing.
  pause
  exit /b 1
)
if not exist "server\plugins" mkdir "server\plugins"
copy /Y "plugin\target\talqoria-core-0.16.0.jar" "server\plugins\TalqoriaCore.jar" >nul
if errorlevel 1 (
  echo [ERROR] Built, but failed to copy JAR into server\plugins.
  pause
  exit /b 1
)

echo.
echo [SUCCESS] TalqoriaCore.jar is now in server\plugins.
echo [NEXT] Put compatible Paper for your Minecraft version as server\paper.jar; then run server\start-windows.bat.
echo [NOTE] This does not mean Paper runtime tests have passed.
pause
exit /b 0
