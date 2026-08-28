package com.rguilbeau.carlauncher.utils.log;

import android.content.Context;
import android.util.Log;


import com.elvishew.xlog.LogConfiguration;
import com.elvishew.xlog.LogLevel;
import com.elvishew.xlog.XLog;
import com.elvishew.xlog.flattener.Flattener2;
import com.elvishew.xlog.printer.AndroidPrinter;
import com.elvishew.xlog.printer.Printer;
import com.elvishew.xlog.printer.file.FilePrinter;
import com.elvishew.xlog.printer.file.clean.FileLastModifiedCleanStrategy;
import com.elvishew.xlog.printer.file.naming.DateFileNameGenerator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Classe utilitaire statique assurant la centralisation du journal de bord (logs)
 * et la capture automatique des exceptions non interceptées (crashs).
 * <p>
 * Redirige les sorties vers le Logcat Android et vers un stockage fichier interne
 * utilisant une rotation journalière conservée sur cinq jours.
 */
public class CarLog {

    /**
     * Indicateur de l'état d'initialisation du service de journalisation.
     */
    private static boolean isInitialized = false;
    /**
     * Constructeur privé pour empêcher l'instanciation de cette classe utilitaire.
     */
    private CarLog() {
        // Constructeur privé intentionnel
    }

    /**
     * Initialise le système de journalisation ainsi que le gestionnaire de capture
     * des erreurs fatales non interceptées.
     * <p>
     * Cette méthode doit être appelée une seule fois lors du démarrage de l'application.
     *
     * @param context Le contexte de l'application permettant d'accéder au répertoire interne.
     */
    public static void init(Context context) {
        if (isInitialized) {
            return;
        }

        LogConfiguration config = new LogConfiguration.Builder()
                .tag("CarLauncher")
                .build();

        // Impression dans la console Logcat
        Printer androidPrinter = new AndroidPrinter();

        // Impression dans des fichiers texte avec rotation journalière conservée sur 5 jours
        File folder = getLogFolder(context);
        Printer filePrinter = new FilePrinter.Builder(folder.getAbsolutePath())
                .fileNameGenerator(new DateFileNameGenerator() {
                    @Override
                    public String generateFileName(int logLevel, long timestamp) {
                        return super.generateFileName(logLevel, timestamp) + ".log";
                    }
                })
                .cleanStrategy(new FileLastModifiedCleanStrategy(5L * 24L * 60L * 60L * 1000L))
                // Définition du formatage sur mesure
                .flattener(new Flattener2() {
                    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());

                    @Override
                    public CharSequence flatten(long timeMillis, int logLevel, String tag, String message) {
                        // Exemple de résultat : 2026-08-28 08:37:25 - [E] Tag : message
                        return dateFormat.format(new Date(timeMillis))
                                + " - [" + LogLevel.getShortLevelName(logLevel) + "] "
                                + tag + " : " + message;
                    }
                })
                .build();

        XLog.init(config, androidPrinter, filePrinter);

        // Capture automatique des erreurs non interceptées
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            XLog.tag("CrashLogger").e("CRASH FATAL SUR LE THREAD [" + thread.getName() + "]", throwable);

            if (defaultHandler != null) {
                defaultHandler.uncaughtException(thread, throwable);
            }
        });

        isInitialized = true;
    }

    /**
     * Récupère le chemin du répertoire où sont stockés les logs
     *
     * @param context Le contexte de l'application permettant d'accéder au répertoire interne.
     * @return Le chemin du répertoire où sont stockés les logs
     */
    public static File getLogFolder(Context context) {
        return new File(context.getFilesDir(), "Logs");
    }

    /**
     * Consigne un message de débogage (DEBUG).
     *
     * @param tag Conteste ou composant émetteur du message.
     * @param msg Le message à journaliser.
     */
    public static void d(String tag, String msg) {
        if (!isInitialized) {
            Log.d(tag, msg);
            return;
        }
        XLog.tag(tag).d(msg);
    }

    /**
     * Consigne un message d'information (INFO).
     *
     * @param tag Contexte ou composant émetteur du message.
     * @param msg Le message à journaliser.
     */
    public static void i(String tag, String msg) {
        if (!isInitialized) {
            Log.i(tag, msg);
            return;
        }
        XLog.tag(tag).i(msg);
    }

    /**
     * Consigne un avertissement (WARN).
     *
     * @param tag Contexte ou composant émetteur du message.
     * @param msg Le message à journaliser.
     */
    public static void w(String tag, String msg) {
        if (!isInitialized) {
            Log.w(tag, msg);
            return;
        }
        XLog.tag(tag).w(msg);
    }

    /**
     * Consigne une erreur (ERROR).
     *
     * @param tag Contexte ou composant émetteur du message.
     * @param msg Le message à journaliser.
     */
    public static void e(String tag, String msg) {
        if (!isInitialized) {
            Log.e(tag, msg);
            return;
        }
        XLog.tag(tag).e(msg);
    }

    /**
     * Consigne une erreur (ERROR) accompagnée de son exception.
     *
     * @param tag Contexte ou composant émetteur du message.
     * @param msg Le message à journaliser.
     * @param tr  L'exception ou l'erreur à associer au journal.
     */
    public static void e(String tag, String msg, Throwable tr) {
        if (!isInitialized) {
            Log.e(tag, msg, tr);
            return;
        }
        XLog.tag(tag).e(msg, tr);
    }
}