@echo off
REM Rebuilds the Cosmic jar (mvnw clean package) and then starts the server.
REM Use this after changing server code. The server must be STOPPED first
REM (run STOP.cmd), otherwise the jar is locked and the build will fail.
cd /d C:\dev\maplestory-lab
powershell -NoProfile -ExecutionPolicy Bypass -File "C:\dev\maplestory-lab\scripts\start-server.ps1" -Build
pause
