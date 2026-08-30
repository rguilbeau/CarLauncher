@echo off
setlocal enabledelayedexpansion

echo ===================================================
echo   Installation de l'application en priv-app (RELEASE)
echo                   + Service Root UDP
echo ===================================================
echo.

:: Chemins vers les fichiers
set APK_PATH=app\release\app-release.apk
set APP_NAME=CarLauncher
set PRIVAPP_XML=privapp-permissions-carlauncher.xml
set SCRIPT_SH=service_root\carlauncher_service_root.sh
set SCRIPT_RC=service_root\carlauncher_service_root.rc

:: --- VERIFICATIONS ---
if not exist "%APK_PATH%" (
    echo [ERREUR] Le fichier APK est introuvable : %APK_PATH%
    echo Veuillez generer l'APK Release.
    echo.
    pause
    exit /b
)

if not exist "%PRIVAPP_XML%" (
    echo [ERREUR] Le fichier de permissions est introuvable : %PRIVAPP_XML%
    echo.
    pause
    exit /b
)

if not exist "%SCRIPT_RC%" (
    echo [ERREUR] Le fichier RC est introuvable : %SCRIPT_RC%
    echo.
    pause
    exit /b
)

:: --- INSTALLATION ---
echo Verification des droits ROOT...
adb root
timeout /t 2 >nul

echo.
echo Desactivation de la securite et montage en ecriture...
adb disable-verity >nul 2>&1
adb remount
timeout /t 2 >nul

echo.
echo Suppression de la version utilisateur (si elle existe)...
adb uninstall com.rguilbeau.carlauncher >nul 2>&1

echo.
echo Creation des dossiers systeme...
adb shell mkdir -p /system/priv-app/%APP_NAME%
adb shell mkdir -p /system/etc/permissions

echo.
echo Copie de l'APK vers le systeme...
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
echo Copie du fichier de permissions privileged...
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
echo Installation de la configuration init (.rc)...
adb push "%SCRIPT_RC%" /system/etc/init/carlauncher_service_root.rc
if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ===================================================
    echo [ERREUR CRITIQUE] L'envoi du fichier RC a echoue.
    echo ===================================================
    pause
    exit /b
)
adb shell chmod 644 /system/etc/init/carlauncher_service_root.rc
adb shell chcon u:object_r:system_file:s0 /system/etc/init/carlauncher_service_root.rc

echo.
echo Redemarrage de l'appareil...
adb reboot

echo.
echo ===================================================
echo   Installation terminee avec succes !
echo ===================================================