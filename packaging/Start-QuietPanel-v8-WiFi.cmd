@echo off
cd /d "%~dp0"
if exist "%~dp0QuietPanelBridge-v8.0.0-WiFi.exe" (
    start "" "%~dp0QuietPanelBridge-v8.0.0-WiFi.exe"
) else (
    start "" "%~dp0QuietPanelBridge.exe"
)
exit /b
