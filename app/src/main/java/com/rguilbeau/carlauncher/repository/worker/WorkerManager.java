package com.rguilbeau.carlauncher.repository.worker;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkRequest;

import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.concurrent.TimeUnit;

/**
 * Façade au-dessus de {@link androidx.work.WorkManager} centralisant l'envoi des requêtes
 * d'écriture vers la base de données distante, sous la contrainte d'une connexion réseau
 * disponible ({@link NetworkType#CONNECTED}).
 * <p>
 * Chaque appel à {@link #addQueue(String, Object...)} empile une requête dans une file
 * persistante (stockée par WorkManager en base Room) : elle survit au kill du process et
 * au redémarrage de l'appareil. Les requêtes sont chaînées sous un même nom de travail unique
 * ({@link ExistingWorkPolicy#APPEND_OR_REPLACE}) afin d'être exécutées dans l'ordre d'arrivée
 * (FIFO), une par une, dès que le réseau redevient disponible.
 * </p>
 *
 * @author rguilbeau
 */
public class WorkerManager {

    private static final String TAG = "WorkerManager";

    /**
     * Nom unique de la chaîne de travail servant de file FIFO pour toutes les écritures en base.
     */
    private static final String QUEUE_NAME = "db_query_queue";

    private static WorkerManager instance;

    private Context appContext;

    private WorkerManager() {
    }

    /**
     * Récupère l'instance unique du gestionnaire.
     *
     * @return L'instance unique de {@link WorkerManager}.
     */
    public static synchronized WorkerManager get() {
        if (instance == null) {
            instance = new WorkerManager();
        }
        return instance;
    }

    /**
     * Initialise le gestionnaire avec le contexte applicatif. À appeler une seule fois,
     * au démarrage de l'application.
     *
     * @param context Le contexte Android permettant d'accéder à WorkManager.
     */
    public static void init(Context context) {
        get().appContext = context.getApplicationContext();
    }

    /**
     * Empile une requête SQL dans la file persistante. Elle sera exécutée par WorkManager
     * dès qu'une connexion réseau sera disponible, en respectant l'ordre d'arrivée.
     *
     * @param query La requête SQL paramétrée à exécuter.
     * @param args  Les valeurs à substituer aux paramètres de la requête, dans l'ordre.
     */
    public void addQueue(String query, Object... args) {
        if (appContext == null) {
            CarLog.e(TAG, "WorkerManager non initialisé, requête perdue : " + query);
            return;
        }

        Data inputData = QueryArgsCodec.encode(query, args);

        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(QueryWorker.class)
                .setInputData(inputData)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
                .build();

        WorkManager.getInstance(appContext)
                .enqueueUniqueWork(QUEUE_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request);

        CarLog.d(TAG, "Requête mise en file persistante : " + query);
    }
}
