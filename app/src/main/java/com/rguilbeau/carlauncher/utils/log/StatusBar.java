package com.rguilbeau.carlauncher.utils.log;

import android.annotation.SuppressLint;
import android.content.Context;

import java.lang.reflect.Method;

public class StatusBar {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "StatusBar";

    /**
     * Constructeur privé pour empêcher l'instanciation de cette classe utilitaire.
     */
    private StatusBar() {
    }

    /**
     * Désactive l'ouverture de la barre de statut d'origine du système (via une API interne réflexive).
     *
     * @param context Le contexte Android permettant d'accéder au service système "statusbar".
     */
    public static void disableOriginalStatusBar(Context context) {
        try {
            @SuppressLint("WrongConstant")
            Object statusBarService = context.getSystemService("statusbar");

            Class<?> statusBarManager = Class.forName("android.app.StatusBarManager");
            Method disableMethod = statusBarManager.getMethod("disable", int.class);

            // Flag magique pour bloquer le slide (0x00010000)
            int DISABLE_EXPAND = 65536;
            disableMethod.invoke(statusBarService, DISABLE_EXPAND);
        } catch (Exception e) {
            CarLog.e(TAG, "Failed to disable status bar", e);
        }

    }

    /**
     * Restaure le comportement d'origine de la barre de statut du système.
     *
     * @param context Le contexte Android permettant d'accéder au service système "statusbar".
     */
    public static void enableOriginalStatusBar(Context context) {
        try {
            @SuppressLint("WrongConstant")
            Object statusBarService = context.getSystemService("statusbar");

            Class<?> statusBarManager = Class.forName("android.app.StatusBarManager");
            Method disableMethod = statusBarManager.getMethod("disable", int.class);

            // 0 = DISABLE_NONE (tout redevient normal)
            disableMethod.invoke(statusBarService, 0);

        } catch (Exception e) {
            CarLog.e(TAG, "Failed to restore status bar", e);
        }
    }
}
