@echo off
chcp 65001 >nul
echo Adding an inbound firewall rule for MuMuLink node.exe (all profiles, needs admin once).
powershell -NoProfile -Command "Start-Process cmd -Verb RunAs -ArgumentList '/c netsh advfirewall firewall add rule name=\"MuMuLink Agent\" dir=in action=allow program=\"%LOCALAPPDATA%\MuMuLink\node.exe\" enable=yes profile=any protocol=any ^& pause'"
