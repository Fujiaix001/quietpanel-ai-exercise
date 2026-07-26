@echo off
setlocal
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0Setup-Bluetooth-PAN.ps1" %*
if errorlevel 1 (
  echo.
  echo QuietPanel Bluetooth PAN setup failed.
  pause
  exit /b 1
)
echo.
echo QuietPanel Bluetooth PAN setup completed.
pause
