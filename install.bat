@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   Installation de l'application en priv-app (RELEASE)
echo ===================================================
echo.

:: Chemin vers l'APK Release genere par Android Studio
set APK_PATH=app\release\app-release.apk
set APP_NAME=CarLauncher
set PRIVAPP_XML=privapp-permissions-carlauncher.xml

if not exist "%APK_PATH%" (
    echo [ERREUR] Le fichier APK est introuvable : %APK_PATH%
    echo Veuillez generer l'APK Release.
    echo (Dans Android Studio : Build -^> Generate Signed Bundle / APK... -^> APK^)
    echo.
    pause
    exit /b
)

if not exist "%PRIVAPP_XML%" (
    echo [ERREUR] Le fichier de permissions est introuvable : %PRIVAPP_XML%
    echo Il doit se trouver a la racine du projet, a cote de install.bat.
    echo.
    pause
    exit /b
)

echo [1/7] Verification des droits ROOT...
adb root
timeout /t 2 >nul

echo.
echo [2/7] Desactivation de la securite et montage en ecriture...
adb disable-verity >nul 2>&1
adb remount
timeout /t 2 >nul

echo.
echo [3/7] Suppression de la version utilisateur (si elle existe)...
adb uninstall com.rguilbeau.carlauncher >nul 2>&1

echo.
echo [4/7] Creation des dossiers systeme...
adb shell mkdir -p /system/priv-app/%APP_NAME%
adb shell mkdir -p /system/etc/permissions

echo.
echo [5/7] Copie de l'APK vers le systeme...
adb push "%APK_PATH%" /system/priv-app/%APP_NAME%/%APP_NAME%.apk

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ===================================================
    echo [ERREUR CRITIQUE] L'envoi de l'APK a echoue.
    echo L'appareil NE VA PAS redemarrer.
    echo ===================================================
    pause
    exit /b
)

adb shell chmod 644 /system/priv-app/%APP_NAME%/%APP_NAME%.apk

echo.
echo [6/7] Copie du fichier de permissions privileged...
adb push "%PRIVAPP_XML%" /system/etc/permissions/%PRIVAPP_XML%

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ===================================================
    echo [ERREUR CRITIQUE] L'envoi du fichier de permissions a echoue.
    echo L'appareil NE VA PAS redemarrer.
    echo ===================================================
    pause
    exit /b
)

adb shell chmod 644 /system/etc/permissions/%PRIVAPP_XML%

echo.
echo [7/7] Redemarrage de l'appareil...
adb reboot

echo.
echo ===================================================
echo   Installation terminee avec succes !
echo ===================================================
pause