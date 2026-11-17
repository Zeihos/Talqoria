@echo off
setlocal
cd /d "%~dp0"
echo TALQORIA Offline Inspector - STOP Minecraft server before continuing.
set "DATA_DIR=%~1"
if "%DATA_DIR%"=="" set "DATA_DIR=%~dp0server\plugins\TalqoriaCore"
where javac >nul 2>&1
if errorlevel 1 (echo JDK 21+ javac required. & pause & exit /b 3)
if not exist "plugin\target\inspector-cli" mkdir "plugin\target\inspector-cli"
javac -encoding UTF-8 -d "plugin\target\inspector-cli" "plugin\src\main\java\ru\talqoria\core\AuditLedger.java" "plugin\src\main\java\ru\talqoria\core\MarketJournal.java" "plugin\src\main\java\ru\talqoria\core\OfflineInspector.java" "plugin\src\main\java\ru\talqoria\core\PlacedBlockLedger.java"
if errorlevel 1 (echo Failed to compile verifier. & pause & exit /b 3)
java -cp "plugin\target\inspector-cli" ru.talqoria.core.OfflineInspector "%DATA_DIR%"
set "EXIT_CODE=%errorlevel%"
echo.
echo Exit code: %EXIT_CODE% ^(0 clean checked files, 2 trade pending, 3 invalid input/data^)
pause
exit /b %EXIT_CODE%
