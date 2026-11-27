@echo off
chcp 65001 >nul
setlocal
set "HERE=%~dp0"
set "NODE=%HERE%node.exe"
if not exist "%NODE%" set "NODE=%LOCALAPPDATA%\MuMuLink\node.exe"
if not exist "%NODE%" set "NODE=node"
echo ============================================
echo  MuMuLink pairing info
echo ============================================
"%NODE%" -e "const fs=require('fs'),path=require('path');let c={};try{c=JSON.parse(fs.readFileSync(path.join(process.argv[1],'config.json'),'utf8'))}catch(e){};console.log('Pairing code : '+(c.pairingCode||'(not set)'));console.log('HTTP port    : '+(c.httpPort||27180));console.log('Console      : http://127.0.0.1:'+(c.httpPort||27180)+'/dashboard');" "%HERE%"
echo.
"%NODE%" "%HERE%ip.js"
echo.
echo Phone app: enter the pairing code above on first connect.
echo Local console: open the dashboard above, no pairing code needed.
echo Change code: dashboard - Settings - pairing code.
echo ============================================
pause
