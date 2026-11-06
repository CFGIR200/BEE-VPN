@echo off
chcp 65001 >nul
title BEE-VPN

rem ─────────────────────────────────────────────────────────────────────────
rem BEE-VPN portable launcher — mirrors the installed app's behavior:
rem the tunnel needs admin (Wintun + 0.0.0.0/0 routes), so the launcher
rem self-elevates once with the standard UAC prompt.
rem ─────────────────────────────────────────────────────────────────────────

net session >nul 2>&1
if %errorlevel% neq 0 (
  echo BEE-VPN needs administrator rights for the VPN tunnel...
  powershell -NoProfile -Command "Start-Process -FilePath '%~dp0app\BEE-VPN.exe' -Verb RunAs"
  exit /b
)

start "" "%~dp0app\BEE-VPN.exe"
