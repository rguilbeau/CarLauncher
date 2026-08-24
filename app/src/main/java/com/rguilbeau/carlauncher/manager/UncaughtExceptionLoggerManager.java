package com.rguilbeau.carlauncher.manager;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.util.Log;

import androidx.appcompat.app.AlertDialog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Gestionnaire d'enregistrement des crashs non interceptés.
 * Capture les exceptions fatales de l'application, les sauvegarde localement
 * et permet de les afficher au redémarrage suivant pour faciliter le débogage.
 */
public class UncaughtExceptionLoggerManager {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) liés aux crashs.
     */
    private static final String TAG = "CrashLogger";

    /**
     * Nom du fichier texte sauvegardé dans l'espace de stockage interne de l'application contenant la trace du dernier crash.
     */
    private static final String CRASH_FILE = "last_crash.txt";

    /**
     * Initialise la capture automatique des erreurs non interceptées.
     * Intercepte le gestionnaire par défaut d'Android pour écrire le crash dans un fichier
     * avant de laisser le système fermer l'application.
     *
     * @param context Le contexte de l'application.
     */
    public static void init(Context context) {
        Context appContext = context.getApplicationContext();
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            saveCrash(appContext, throwable);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });
    }

    /**
     * Affiche une boîte de dialogue contenant la trace (stack trace) du dernier crash, s'il y en a eu un.
     * Propose une option pour copier le texte dans le presse-papiers.
     *
     * @param activity L'activité sur laquelle afficher la boîte de dialogue (généralement MainActivity au démarrage).
     */
    public static void showLastCrash(Activity activity) {
        String crashLog = getAndClearCrashLog(activity);
        if (crashLog == null || crashLog.isEmpty()) return;

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Rapport de crash (Wakeup)")
                .setMessage(crashLog)
                .setPositiveButton("OK", null)
                .setNeutralButton("Copier", (dialogInterface, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("Crash Log", crashLog);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(clip);
                    }
                })
                .show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
    }

    /**
     * Sauvegarde la pile d'exécution (stack trace) de l'exception fatale dans un fichier texte local.
     *
     * @param context   Le contexte de l'application pour accéder au répertoire des fichiers.
     * @param throwable L'exception interceptée ayant causé le crash.
     */
    private static void saveCrash(Context context, Throwable throwable) {
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            throwable.printStackTrace(pw);
            String stackTrace = sw.toString();

            File file = new File(context.getFilesDir(), CRASH_FILE);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(stackTrace.getBytes());
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de la sauvegarde du crash", e);
        }
    }

    /**
     * Lit le contenu du fichier de crash s'il existe, puis le supprime pour éviter qu'il
     * ne s'affiche à nouveau lors des lancements suivants.
     *
     * @param context Le contexte de l'application pour accéder au fichier.
     * @return La chaîne de caractères contenant la trace du crash, ou null si aucun crash récent n'a été enregistré.
     */
    private static String getAndClearCrashLog(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.exists()) return null;

        try {
            byte[] bytes = new byte[(int) file.length()];
            try (FileInputStream fis = new FileInputStream(file)) {
                fis.read(bytes);
            }
            file.delete();
            return new String(bytes);
        } catch (Exception e) {
            file.delete();
            return null;
        }
    }
}