@echo off
setlocal
cd /d "%~dp0"
set "APK="
for %%F in (QuietPanel-v*.apk) do set "APK=%%F"
if not defined APK (
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
echo QuietPanel Android v6 installed and started.
pause

