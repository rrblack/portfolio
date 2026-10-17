@echo off
title Harness Bridge
cd /d "%~dp0"
where node >nul 2>nul
if errorlevel 1 (
  echo Node.js is not installed or not on PATH. Install the LTS version from https://nodejs.org and try again.
  pause
  exit /b 1
)
node "%~dp0harness-bridge.mjs" %*
echo.
echo The bridge has stopped.
pause
