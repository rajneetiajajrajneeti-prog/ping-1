@echo off
title MDM Agent Launcher
echo.
echo  ============================
echo   MDM Agent - Starting Up
echo  ============================
echo.

echo  [1/2] Starting Backend (port 4000)...
start "MDM Backend" cmd /k "cd /d "%~dp0backend" && node server.js"

timeout /t 2 /nobreak > nul

echo  [2/2] Starting Dashboard (port 5173)...
start "MDM Dashboard" cmd /k "cd /d "%~dp0dashboard" && npm run dev -- --open"

echo.
echo  Both services started!
echo.
echo  Backend  : http://localhost:4000
echo  Dashboard: http://localhost:5173
echo.
echo  (You can close this window)
pause > nul
