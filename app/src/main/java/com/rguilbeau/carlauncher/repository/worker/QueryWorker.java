package com.rguilbeau.carlauncher.repository.worker;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Data;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.rguilbeau.carlauncher.repository.client.IClient;
import com.rguilbeau.carlauncher.repository.client.NeonClient;
import com.rguilbeau.carlauncher.utils.log.CarLog;

/**
 * Unité de travail WorkManager exécutant une requête SQL en attente dans la file persistante.
 * <p>
 * WorkManager ne (re)lance ce worker que lorsque la contrainte réseau est satisfaite
 * ({@link androidx.work.NetworkType#CONNECTED}), et le fait persister en base (Room) afin de
 * survivre au redémarrage du processus ou de l'appareil tant qu'il n'a pas réussi.
 * </p>
 */
public class QueryWorker extends Worker {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "QueryWorker";

    /**
     * Client de base de données utilisé pour exécuter la requête SQL en attente.
     */
    private final IClient client = new NeonClient();

    /**
     * Constructeur requis par WorkManager pour instancier ce worker.
     *
     * @param context Le contexte Android fourni par WorkManager.
     * @param params  Les paramètres du travail, incluant les données d'entrée.
     */
    public QueryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    /**
     * Décode la requête et ses arguments depuis les données d'entrée, puis l'exécute via {@link #client}.
     *
     * @return {@link Result#success()} en cas de succès, {@link Result#retry()} en cas d'échec réseau/SQL,
     * ou {@link Result#failure()} si les données d'entrée sont invalides.
     */
    @NonNull
    @Override
    public Result doWork() {
        Data input = getInputData();
        String query = QueryArgsCodec.decodeQuery(input);

        if (query == null) {
            CarLog.e(TAG, "Requête invalide (Data manquante), abandon du worker.");
            return Result.failure();
        }

        Object[] args = QueryArgsCodec.decodeArgs(input);
        boolean success = client.exec(query, args);

        if (success) {
            return Result.success();
        }

        CarLog.w(TAG, "Échec d'exécution, nouvelle tentative planifiée : " + query);
        return Result.retry();
    }
}
