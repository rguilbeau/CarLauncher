@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   Installation de l'application en priv-app (RELEASE)
echo ===================================================
echo.

:: Chemin vers l'APK Release genere par Android Studio
set APK_PATH=app\release\app-release.apk
set APP_NAME=CarLauncher

if not exist "%APK_PATH%" (
    echo [ERREUR] Le fichier APK est introuvable : %APK_PATH%
    echo Veuillez generer l'APK Release.
    echo (Dans Android Studio : Build -^> Generate Signed Bundle / APK... -^> APK^)
    echo.
    pause
    exit /b
)

echo [1/6] Verification des droits ROOT...
adb root
timeout /t 2 >nul

echo.
echo [2/6] Desactivation de la securite et montage en ecriture...
adb disable-verity >nul 2>&1
adb remount
timeout /t 2 >nul

echo [3/6] Suppression de la version utilisateur (si elle existe)...
adb uninstall com.rguilbeau.carlauncher >nul 2>&1

echo.
echo [4/6] Creation du dossier /system/priv-app/%APP_NAME%...
adb shell mkdir -p /system/priv-app/%APP_NAME%

echo.
echo [5/6] Copie de l'APK vers le systeme...
adb push "%APK_PATH%" /system/priv-app/%APP_NAME%/%APP_NAME%.apk

:: On verifie si la commande precedente (adb push) a echoue
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ===================================================
    echo [ERREUR CRITIQUE] L'envoi de l'APK a echoue.
    echo L'appareil NE VA PAS redemarrer.
    echo ===================================================
    pause
    exit /b
)

:: Si on arrive ici, la copie a reussi
adb shell chmod 644 /system/priv-app/%APP_NAME%/%APP_NAME%.apk

echo.
echo [6/6] Redemarrage de l'appareil...
adb reboot

echo.
echo ===================================================
echo   Installation terminee avec succes ! 
echo ===================================================
pause