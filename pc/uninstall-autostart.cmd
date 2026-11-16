@echo off
setlocal
chcp 65001 >nul

set "SHIM=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\MuMuLinkAgent.cmd"
set "OLD_LNK=%APPDATA%\Microsoft\Windows\Start Menu\Programs\Startup\MuMuLinkAgent.lnk"
set "FOUND="

if exist "%SHIM%" (
  del /f /q "%SHIM%"
  set "FOUND=1"
  echo 已取消登录自启：%SHIM%
)
if exist "%OLD_LNK%" (
  del /f /q "%OLD_LNK%"
  set "FOUND=1"
  echo 已删除旧的快捷方式：%OLD_LNK%
)
if not defined FOUND echo 未发现自启项，无需卸载。
if not defined MUMULINK_NO_PAUSE pause
