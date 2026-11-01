@echo off
setlocal
call gradlew.bat :app:testFossDebugUnitTest :app:assembleFossDebug --stacktrace --console=plain
if errorlevel 1 (
  echo.
  echo Build failed. Check JDK 21 and Android SDK 36 installation.
  exit /b 1
)
echo.
echo APK READY:
echo %CD%\app\build\outputs\apk\foss\debug\app-foss-debug.apk
endlocal
