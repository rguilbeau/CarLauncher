package com.rguilbeau.carlauncher.utils.log;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dépôt de données (Repository) responsable de la lecture, de l'extraction,
 * du chargement différé et de la suppression des fichiers de journaux d'événements.
 */
public class LogRepository {

    /**
     * Répertoire contenant les fichiers de journaux d'événements.
     */
    private final File logsFolder;

    /**
     * Exécuteur mono-thread dédié à la lecture asynchrone des fichiers.
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Gestionnaire permettant de poster les résultats sur le thread principal (UI).
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Interface de rappel (callback) pour la transmission asynchrone des résultats ou des erreurs.
     *
     * @param <T> Type de donnée retourné en cas de succès.
     */
    public interface LogCallback<T> {
        /**
         * Méthode appelée lors du succès du traitement.
         *
         * @param result Le résultat produit par l'opération.
         */
        void onSuccess(T result);

        /**
         * Méthode appelée lorsqu'une erreur survient durant le traitement.
         *
         * @param e L'exception capturée.
         */
        void onError(Exception e);
    }

    /**
     * Constructeur initialisant le répertoire d'accès aux fichiers de journaux d'événements.
     *
     * @param context Le contexte de l'application.
     */
    public LogRepository(Context context) {
        this.logsFolder = new File(context.getFilesDir(), "Logs");
    }

    /**
     * Récupère la liste des dates disponibles basées sur les noms des fichiers enregistrés.
     * Les dates sont triées de la plus récente à la plus ancienne.
     *
     * @return Une liste de chaînes de caractères représentant les dates.
     */
    public List<String> getAvailableDates() {
        List<String> dates = new ArrayList<>();
        if (!logsFolder.exists()) {
            return dates;
        }

        File[] files = logsFolder.listFiles((dir, name) -> name.endsWith(".log"));
        if (files != null) {
            Arrays.sort(files, (f1, f2) -> f2.getName().compareTo(f1.getName()));

            for (File file : files) {
                String name = file.getName();
                String dateStr = name.substring(0, name.lastIndexOf(".log"));
                dates.add(dateStr);
            }
        }
        return dates;
    }

    /**
     * Lit le contenu du journal d'événements pour une date donnée de manière asynchrone.
     * Les résultats sont retournés sur le thread principal d'affichage via l'interface de rappel.
     *
     * @param date     La date ciblée au format "yyyy-MM-dd".
     * @param callback L'interface de rappel recevant la liste des lignes de log ou l'erreur survenue.
     */
    public void getLogsForDateAsync(String date, LogCallback<List<String>> callback) {
        executor.execute(() -> {
            try {
                List<String> lines = readLogsForDateSync(date);
                mainHandler.post(() -> callback.onSuccess(lines));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Lit de manière synchrone le fichier de journal correspondant à la date spécifiée.
     *
     * @param date La date ciblée au format "yyyy-MM-dd".
     * @return La liste des lignes contenues dans le fichier.
     * @throws IOException Exception levée en cas d'erreur lors de la lecture du fichier.
     */
    public List<String> readLogsForDateSync(String date) throws IOException {
        File logFile = new File(logsFolder, date + ".log");
        List<String> logLines = new ArrayList<>();

        if (!logFile.exists()) {
            return logLines;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(logFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logLines.add(line);
            }
        }

        return logLines;
    }

    /**
     * Supprime de manière asynchrone l'intégralité des fichiers de logs du répertoire.
     *
     * @param callback L'interface de rappel notifiée lors de la complétion.
     */
    public void clearAllLogsAsync(LogCallback<Void> callback) {
        executor.execute(() -> {
            try {
                clearAllLogsSync();
                mainHandler.post(() -> callback.onSuccess(null));
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError(e));
            }
        });
    }

    /**
     * Supprime de manière synchrone tous les fichiers se terminant par ".log" dans le répertoire.
     */
    private void clearAllLogsSync() {
        if (logsFolder.exists()) {
            File[] files = logsFolder.listFiles((dir, name) -> name.endsWith(".log"));
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
        }
    }
}