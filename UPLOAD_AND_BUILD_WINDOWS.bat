@echo off
setlocal
cd /d "%~dp0"
echo ==========================================
echo SmartScan Pro - Upload and Auto Build APK
echo ==========================================
where git >nul 2>&1 || (echo ERROR: Git is missing. Install from https://git-scm.com/download/win & pause & exit /b 1)
where gh >nul 2>&1 || (echo ERROR: GitHub CLI is missing. Install from https://cli.github.com/ & pause & exit /b 1)
gh auth status >nul 2>&1 || gh auth login
if errorlevel 1 (echo GitHub login failed & pause & exit /b 1)
set /p REPO=New repository name [SmartScan-Pro]: 
if "%REPO%"=="" set "REPO=SmartScan-Pro"
if exist .git (echo ERROR: This folder is already a git repository & pause & exit /b 1)
git init -b main || goto failed
git config user.name >nul 2>&1 || git config user.name "SmartScan Pro Builder"
git config user.email >nul 2>&1 || git config user.email "builder@users.noreply.github.com"
git add . || goto failed
git commit -m "SmartScan Pro V3 complete Android source and auto APK build" || goto failed
gh repo create "%REPO%" --private --source=. --remote=origin --push || goto failed
echo.
echo SUCCESS. Your repository was created and pushed.
echo GitHub Actions starts automatically on push.
echo Open the repository Actions tab to download SmartScan-Pro.apk.
gh repo view --web
pause
exit /b 0
:failed
echo Upload failed. Read the error above; your old repository was not modified.
pause
exit /b 1
