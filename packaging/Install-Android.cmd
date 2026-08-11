@echo off
setlocal
cd /d "%~dp0"
set "APK=QuietPanel.apk"
if not exist "%APK%" (
  echo QuietPanel APK not found.
  pause
  exit /b 1
)

adb.exe install -r "%APK%"
if errorlevel 1 (
  echo Android installation failed.
  pause
  exit /b 1
)

adb.exe shell am start -n com.quietpanel.client/.MainActivity
echo.
echo QuietPanel Android installed and started.
pause

