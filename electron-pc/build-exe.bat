@echo off
cd /d %~dp0
set CSC_IDENTITY_AUTO_DISCOVERY=false
if exist "%LOCALAPPDATA%\electron-builder\Cache\winCodeSign" rmdir /s /q "%LOCALAPPDATA%\electron-builder\Cache\winCodeSign"
echo Copying the TindaPOS web app...
if exist www rmdir /s /q www
xcopy /e /i /y ..\www www >nul
echo Installing build tools (first time takes a few minutes)...
call npm install
if errorlevel 1 goto fail
echo Building the Windows installer and portable EXE...
call npm run dist
if errorlevel 1 goto fail
echo.
echo DONE. Your files are in the dist folder:
dir /b dist\*.exe
explorer dist
pause
exit /b 0
:fail
echo.
echo BUILD FAILED. Copy the red error text above and send it to Claude.
pause
exit /b 1
