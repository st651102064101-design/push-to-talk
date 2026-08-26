@echo off
setlocal
title PTT Docker Launcher
cd /d "%~dp0"

echo ========================================
echo       PTT Relay Server - Docker
echo ========================================
echo.

where docker >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker is not installed or is not in PATH.
    pause
    exit /b 1
)

docker info >nul 2>&1
if errorlevel 1 (
    echo ERROR: Docker Desktop is not running.
    echo Open Docker Desktop, wait until it is ready, then try again.
    pause
    exit /b 1
)

echo Checking secure PTT credentials...
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0server\Initialize-PttCredentials.ps1"
if errorlevel 1 (
    echo ERROR: Could not create or validate server credentials.
    pause
    exit /b 1
)

echo Building and starting PTT Server container...
docker compose -f "%~dp0compose.yaml" up -d --build
if errorlevel 1 (
    echo ERROR: Docker could not start the PTT Server.
    echo Run: docker compose logs ptt-server
    pause
    exit /b 1
)

echo.
docker compose -f "%~dp0compose.yaml" ps
echo.
echo PTT Server is running in Docker.
echo Relay     : ws://SERVER-IP:8181
echo Dashboard : http://localhost:8181/
echo.
echo Use the Dashboard Username and Password from server\.env.
echo Keep that file private and do not include it in screenshots.
start "" "http://localhost:8181/"
echo.
pause
endlocal
