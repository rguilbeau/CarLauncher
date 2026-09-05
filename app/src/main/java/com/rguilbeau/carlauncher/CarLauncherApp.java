package com.rguilbeau.carlauncher;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.widget.TextView;

import com.elvishew.xlog.XLog;
import com.rguilbeau.carlauncher.utils.log.CarLog;
import com.rguilbeau.carlauncher.utils.log.StatusBar;

/**
 * Classe Application globale du projet Car Launcher.
 * Instanciée par le système avant toutes les autres activités ou services de l'application.
 * Sert de point d'entrée pour l'initialisation des composants globaux.
 */
public class CarLauncherApp extends Application {

    /**
     * Tag utilisé pour l'écriture des logs
     */
    private static final String TAG = "CarLauncherApp";

    /**
     * Appelée au tout premier démarrage de l'application.
     * Initialise le gestionnaire d'exceptions pour capturer et logger les crashs
     * non interceptés avant que l'application ne se ferme.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        CarLog.init(this);

        // Capture automatique des erreurs non interceptées
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            CarLog.e("UNCAUGHT EXCEPTION", "CRASH FATAL SUR LE THREAD [" + thread.getName() + "]", throwable);

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }

            StatusBar.enableOriginalStatusBar(getApplicationContext());
        });

        CarLog.i(TAG, "----------------------------------------");
        CarLog.i(TAG, "Current version: " + getCurrentVersion(this));

        StatusBar.disableOriginalStatusBar(getApplicationContext());
    }

    /**
     * Récupère la version actuelle de l'application
     *
     * @param context Le context android
     * @return La version installée
     */
    public static String getCurrentVersion(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (Exception e) {
            CarLog.e(TAG, "Failed to find current version", e);
            return "inconnue";
        }
    }
}