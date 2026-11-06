@echo off
title Clean Your TV - Wallpaper Sync
setlocal enabledelayedexpansion

set "PY_SCRIPT=%~dp0sync_wallpapers.py"

if not exist "%PY_SCRIPT%" (
    echo [ERROR] Python script not found at: %PY_SCRIPT%
    pause
    exit /b 1
)

if "%~1"=="" (
    python "%PY_SCRIPT%"
) else (
    python "%PY_SCRIPT%" "%~1"
)

exit /b %ERRORLEVEL%

