@echo off
setlocal

echo =======================================================
echo  CleanYourTV - Install Release APK to TV
echo =======================================================
echo.

set "TV_IP=192.168.1.37:5555"
set "APK_PATH=%~dp0launcher\app\build\outputs\apk\release\app-release.apk"

if not exist "%APK_PATH%" (
    echo [ERROR] Release APK not found at:
    echo         %APK_PATH%
    echo Please run "Build Release APK.bat" first!
    echo.
    pause
    exit /b 1
)

echo [1/3] Connecting to TV at %TV_IP%...
adb connect %TV_IP%
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Could not connect to TV at %TV_IP%. Make sure TV is turned on and Wi-Fi debugging is enabled.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [2/3] Installing APK to TV...
adb -s %TV_IP% install -r "%APK_PATH%"
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Installation failed!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [3/3] Verifying launcher installation...
adb -s %TV_IP% shell pm list packages | findstr "com.gathin.tvhome"

echo.
echo =======================================================
echo  INSTALLATION COMPLETE!
echo =======================================================
echo  Launcher is updated and running on TV (%TV_IP%).
echo =======================================================
echo.
pause

