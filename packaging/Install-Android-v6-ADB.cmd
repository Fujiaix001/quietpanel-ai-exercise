@echo off
setlocal
cd /d "%~dp0"
set "APK=QuietPanel-v6.8.13-ADB.apk"
if not exist "%APK%" (
  for %%F in (QuietPanel-v6*.apk) do set "APK=%%F"
)
if not exist "%APK%" (
  echo QuietPanel v6 ADB APK not found.
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
echo QuietPanel Android v6.8.13 ADB installed and started.
pause
