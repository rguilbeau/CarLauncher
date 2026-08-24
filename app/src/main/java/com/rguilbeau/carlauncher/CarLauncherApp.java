package com.rguilbeau.carlauncher;

import android.app.Application;

import com.rguilbeau.carlauncher.manager.UncaughtExceptionLoggerManager;

/**
 * Classe Application globale du projet Car Launcher.
 * Instanciée par le système avant toutes les autres activités ou services de l'application.
 * Sert de point d'entrée pour l'initialisation des composants globaux.
 */
public class CarLauncherApp extends Application {

    /**
     * Appelée au tout premier démarrage de l'application.
     * Initialise le gestionnaire d'exceptions pour capturer et logger les crashs
     * non interceptés avant que l'application ne se ferme.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        UncaughtExceptionLoggerManager.init(this);
    }
}