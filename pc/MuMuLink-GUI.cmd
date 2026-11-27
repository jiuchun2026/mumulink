@echo off
chcp 65001 >nul
title MuMuLink Console
set HERE=%~dp0
rem Start the PC agent in its own window (if already running, the new instance exits harmlessly).
rem If an agent is already listening, reuse it instead of starting a second one.
curl -s -m 2 -o "%TEMP%\mumulink_probe.json" http://127.0.0.1:27180/api/hello >nul 2>&1
if errorlevel 1 (
  start "MuMuLink Agent" "%HERE%start-agent.cmd"
  timeout /t 3 >nul
)
set URL=http://127.0.0.1:27180/dashboard
if exist "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" (
  start "" "%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe" --app=%URL%
) else if exist "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" (
  start "" "%ProgramFiles%\Microsoft\Edge\Application\msedge.exe" --app=%URL%
) else (
  start "" %URL%
)
