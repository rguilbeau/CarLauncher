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

    private static final String TAG = "QueryWorker";

    private final IClient client = new NeonClient();

    public QueryWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

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
