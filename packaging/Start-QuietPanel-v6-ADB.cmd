@echo off
cd /d "%~dp0"
if exist "%~dp0QuietPanelBridge-v6.9.0-ADB.exe" (
    start "" "%~dp0QuietPanelBridge-v6.9.0-ADB.exe"
) else (
    start "" "%~dp0QuietPanelBridge.exe"
)
exit /b
