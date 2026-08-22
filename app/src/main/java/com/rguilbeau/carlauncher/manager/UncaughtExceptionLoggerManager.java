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

public class UncaughtExceptionLoggerManager {
    private static final String TAG = "CrashLogger";
    private static final String CRASH_FILE = "last_crash.txt";

    /**
     * Initialise la capture automatique des erreurs non interceptées.
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
     * Affiche un dialogue avec la stack trace du dernier crash s'il existe.
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