@echo off
setlocal

echo =======================================================
echo  CleanYourTV - Build Release APK
echo =======================================================
echo.

set "STUDIO_JBR=C:\Program Files\Android\Android Studio\jbr"
if exist "%STUDIO_JBR%\bin\java.exe" (
    echo [OK] Using Android Studio JDK: %STUDIO_JBR%
    set "JAVA_HOME=%STUDIO_JBR%"
    set "PATH=%STUDIO_JBR%\bin;%PATH%"
) else (
    echo [INFO] Using system JAVA_HOME: %JAVA_HOME%
)

cd /d "%~dp0launcher"

echo [1/2] Running Unit Tests...
call gradlew.bat testDebugUnitTest
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] Unit tests failed! Build aborted.
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo [2/2] Building Release APK...
call gradlew.bat assembleRelease
if %ERRORLEVEL% neq 0 (
    echo.
    echo [ERROR] assembleRelease failed!
    pause
    exit /b %ERRORLEVEL%
)

echo.
echo =======================================================
echo  BUILD SUCCESSFUL!
echo =======================================================
set "APK_PATH=%~dp0launcher\app\build\outputs\apk\release\app-release.apk"
if exist "%APK_PATH%" (
    for %%F in ("%APK_PATH%") do (
        echo  APK: %%~fF
        echo  Size: %%~zF bytes
    )
)
echo =======================================================
echo.
pause

