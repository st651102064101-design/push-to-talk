@echo off
setlocal
title PTT Server Launcher

set "SERVER_DIR=%~dp0server"
set "SERVER_ENV=%~dp0server\.env"
set "DASHBOARD_URL=http://localhost:8181/"

echo ========================================
echo        PTT Relay Server Launcher
echo ========================================
echo.

if not exist "%SERVER_DIR%\package.json" (
    echo ERROR: Server project was not found:
    echo %SERVER_DIR%
    pause
    exit /b 1
)

where node >nul 2>&1
if errorlevel 1 (
    echo ERROR: Node.js is not installed or is not in PATH.
    echo Install Node.js, then run this file again.
    pause
    exit /b 1
)

where npm >nul 2>&1
if errorlevel 1 (
    echo ERROR: npm is not installed or is not in PATH.
    pause
    exit /b 1
)

if not exist "%SERVER_ENV%" (
    echo Creating secure PTT credentials...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$rng = [Security.Cryptography.RandomNumberGenerator]::Create(); function New-Secret([int]$size) { $bytes = New-Object byte[] $size; $rng.GetBytes($bytes); return [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant() }; $userToken = New-Secret 32; $adminToken = New-Secret 32; $dashboardPassword = New-Secret 16; @('PTT_USER_TOKEN=' + $userToken, 'PTT_ADMIN_TOKEN=' + $adminToken, 'PTT_DASHBOARD_USER=admin', 'PTT_DASHBOARD_PASSWORD=' + $dashboardPassword) | Set-Content -Encoding ASCII -LiteralPath '%SERVER_ENV%'; $rng.Dispose()"
    if errorlevel 1 (
        echo ERROR: Could not create server credentials.
        pause
        exit /b 1
    )
)

if not exist "%SERVER_DIR%\node_modules\ws\package.json" (
    echo Installing server dependencies...
    pushd "%SERVER_DIR%"
    call npm install
    if errorlevel 1 (
        popd
        echo ERROR: npm install failed.
        pause
        exit /b 1
    )
    popd
)

call :check_server
if not errorlevel 1 (
    echo PTT Server is already running on port 8181.
    call :show_credentials
    echo Opening Dashboard...
    start "" "%DASHBOARD_URL%"
    pause
    exit /b 0
)

echo Starting PTT Server on port 8181...
start "PTT Relay Server" /D "%SERVER_DIR%" cmd /k npm start

set /a RETRIES=0
:wait_for_server
timeout /t 1 /nobreak >nul
call :check_server
if not errorlevel 1 goto server_ready
set /a RETRIES+=1
if %RETRIES% GEQ 10 goto server_failed
goto wait_for_server

:server_ready
echo Server is ready.
call :show_credentials
echo Opening Dashboard: %DASHBOARD_URL%
start "" "%DASHBOARD_URL%"
pause
exit /b 0

:server_failed
echo ERROR: Server did not respond on port 8181.
echo Check the PTT Relay Server window for details.
pause
exit /b 1

:check_server
powershell -NoProfile -ExecutionPolicy Bypass -Command "$client = New-Object Net.Sockets.TcpClient; try { $client.Connect('127.0.0.1', 8181); exit 0 } catch { exit 1 } finally { $client.Dispose() }" >nul 2>&1
exit /b %errorlevel%

:show_credentials
for /f "usebackq tokens=1,* delims==" %%A in ("%SERVER_ENV%") do set "%%A=%%B"
echo.
echo ----------------------------------------
echo Android User Token  : %PTT_USER_TOKEN%
echo Android Admin Token : %PTT_ADMIN_TOKEN%
echo Dashboard Username  : %PTT_DASHBOARD_USER%
echo Dashboard Password  : %PTT_DASHBOARD_PASSWORD%
echo ----------------------------------------
echo Keep these credentials private.
echo User Token can use rooms 1-4. Admin Token can also use All.
echo.
exit /b 0
