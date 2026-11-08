@echo off
setlocal
cd /d %~dp0
echo === TindaPOS.ph Scanner: build APK ===

where java >nul 2>nul
if errorlevel 1 goto nojava
where gradle >nul 2>nul
if errorlevel 1 goto nogradle

if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk"
if not exist "%ANDROID_HOME%\platforms" goto nosdk

set "SDKP=%ANDROID_HOME:\=/%"
> android-scanner\local.properties echo sdk.dir=%SDKP%

cd android-scanner
call gradle --no-daemon assembleDebug
if errorlevel 1 goto fail
cd ..
copy /y android-scanner\app\build\outputs\apk\debug\app-debug.apk TindaPOS-Scanner.apk >nul
echo.
echo DONE. Your APK: %~dp0TindaPOS-Scanner.apk
explorer /select,"%~dp0TindaPOS-Scanner.apk"
pause
exit /b 0

:nojava
echo Java 17 was not found. Install JDK 17 (for example Temurin 17) and open a new Command Prompt.
goto fail
:nogradle
echo Gradle was not found. Install Gradle 8.9 or newer, add its bin folder to PATH, and open a new Command Prompt.
goto fail
:nosdk
echo Android SDK not found at %ANDROID_HOME%
echo Install Android Studio once (it installs the SDK), or set ANDROID_HOME to your SDK folder.
goto fail
:fail
echo.
echo BUILD FAILED. Copy the red error text above and send it to Claude.
pause
exit /b 1
