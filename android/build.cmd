@echo off
setlocal
set "JAVA_HOME=%JAVA_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%~dp0"
call gradlew.bat %*
