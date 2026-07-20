@echo off
cd /d "%~dp0"
QuietPanelBridge.exe
if errorlevel 1 pause

