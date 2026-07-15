@echo off
setlocal EnableDelayedExpansion

echo ===================================
echo   Connected ADB Devices:
echo ===================================
adb devices -l
echo.

:: Default package name
set "PACKAGE_NAME=com.mdaksh.m3uvideoplayer"

echo ===================================
echo   Building and Installing App...
echo ===================================
echo.

:: Build and install the debug APK
call gradlew.bat installDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ========================================
    echo   BUILD FAILED! App was NOT updated.
    echo ========================================
    echo.
    pause
    exit /b 1
)

echo.
echo ===================================
echo   Build Successful! Launching App...
echo ===================================
echo.

:: Force stop any running instance
adb shell am force-stop %PACKAGE_NAME%
timeout /t 1 /nobreak >nul

:: Launch the freshly installed app
adb shell am start -n %PACKAGE_NAME%/.MainActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER

echo.
echo App updated and launched successfully!
echo.
pause
