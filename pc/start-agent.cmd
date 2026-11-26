@echo off
setlocal enabledelayedexpansion
chcp 65001 >nul
cd /d "%~dp0"

rem Windows firewall rules are per-exe-path AND per-network-profile.
rem The system node.exe may only have a Public-profile rule while the campus
rem Wi-Fi is often classified as Private, so inbound gets blocked.
rem Fix: run node.exe from a user-writable path; Windows creates a
rem Private+Public allow rule for that new path (click Allow if prompted).
set "NODE_BIN="
set "LOCALNODE=%LOCALAPPDATA%\MuMuLink\node.exe"

if exist "%~dp0node.exe" set "NODE_BIN=%~dp0node.exe"
if not defined NODE_BIN if exist "%LOCALNODE%" set "NODE_BIN=%LOCALNODE%"

if not defined NODE_BIN (
  set "SYSNODE="
  if exist "D:\app\new folder\node.exe" set "SYSNODE=D:\\app\\new folder\node.exe"
  if not defined SYSNODE if exist "D:\app\New Folder\node.exe" set "SYSNODE=D:\\app\\New Folder\node.exe"
  if not defined SYSNODE (
    for /f "delims=" %%n in ('where node 2^>nul') do if not defined SYSNODE set "SYSNODE=%%n"
  )
  if defined SYSNODE (
    if not exist "%LOCALAPPDATA%\MuMuLink" mkdir "%LOCALAPPDATA%\MuMuLink" >nul 2>nul
    echo Preparing local Node runtime: %LOCALNODE%
    copy /y "!SYSNODE!" "%LOCALNODE%" >nul 2>nul
    if exist "%LOCALNODE%" set "NODE_BIN=%LOCALNODE%"
  )
)

if not defined NODE_BIN (
  echo [ERROR] Node.js 18+ not found. Install Node, or put node.exe here.
  pause
  exit /b 1
)

for /f "usebackq delims=" %%i in (`"%NODE_BIN%" ip.js`) do set "HOSTPORT=%%i"
if not defined HOSTPORT set "HOSTPORT=127.0.0.1:27180"
set "MUMULINK_LOG=%~dp0agent.log"
title MuMuLink Agent - !HOSTPORT!

echo ============================================
echo  MuMuLink PC Agent
echo  Pair page: http://!HOSTPORT!/
echo  Node:      !NODE_BIN!
echo  Config:    %cd%\config.json
echo.
echo  Phone cannot connect?
echo   1) If Windows Firewall pops up, click Allow access.
echo   2) Phone and PC must be on the same Wi-Fi (campus AP may isolate clients).
echo   3) USB fallback: adb reverse tcp:27180 tcp:27180 (plus 27182, 27183)
echo      then connect the app to 127.0.0.1:27180.
echo  Close this window to stop; auto-restart 3s after a crash (log: agent.log).
echo ============================================

:loop
"%NODE_BIN%" agent.js
set "EXITCODE=%ERRORLEVEL%"
echo.
echo [%DATE% %TIME%] Agent exited (rc=%EXITCODE%). Log: %~dp0agent.log
echo [%DATE% %TIME%] Auto-restart in 3s... close this window to stop.
timeout /t 3 /nobreak >nul
goto loop
