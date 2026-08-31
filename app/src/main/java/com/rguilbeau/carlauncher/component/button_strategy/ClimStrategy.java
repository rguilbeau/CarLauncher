package com.rguilbeau.carlauncher.component.button_strategy;

import android.content.Context;
import android.content.Intent;

import com.rguilbeau.carlauncher.utils.log.CarLog;

public class ClimStrategy implements ButtonStrategy {
    /**
     * Tag d'identification utilisé pour les journaux d'erreurs (Logcat).
     */
    private static final String TAG = "ButtonAppDrawer";

    /**
     * Type d'identification du bouton Clim"
     */
    public static final String TYPE = "clim_popup";

    /**
     * Gère le clic simple en lançant le broadcast pour afficher le popup clim
     *
     * @param context Le contexte Android courant.
     */
    @Override
    public void onClick(Context context) {
        try {
            Intent intent = new Intent("com.qf.vehicle.action.ac_popup");
            context.sendBroadcast(intent);

            CarLog.i(TAG, "broadcast com.qf.vehicle.action.ac_popup sent");
        } catch (Exception e) {
            CarLog.e(TAG, "Failed to send broadcast com.qf.vehicle.action.ac_popup", e);
        }
    }

    /**
     * Intercepte l'appui long et l'ignore silencieusement.
     *
     * @param context Le contexte Android courant.
     * @return true, pour indiquer que l'événement a bien été intercepté (empêche un clic intempestif).
     */
    @Override
    public boolean onLongClick(Context context) {
        // Comportement volontairement ignoré pour ce type de bouton immuable
        return true;
    }
}
