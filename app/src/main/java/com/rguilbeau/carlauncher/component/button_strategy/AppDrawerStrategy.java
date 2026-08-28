package com.rguilbeau.carlauncher.component.button_strategy;

import android.content.Context;
import android.content.Intent;


import com.rguilbeau.carlauncher.AppDrawerActivity;
import com.rguilbeau.carlauncher.utils.log.CarLog;

/**
 * Stratégie de comportement pour le bouton principal ouvrant le tiroir d'applications (App Drawer).
 * <p>
 * <ul>
 *     <li><b>Clic simple :</b> Ouvre l'activité listant toutes les applications ({@link AppDrawerActivity}).</li>
 *     <li><b>Appui long :</b> Ignore l'événement car ce bouton ne peut pas être réassigné.</li>
 * </ul>
 * </p>
 *
 * @author rguilbeau
 * @version 1.0
 */
public class AppDrawerStrategy implements ButtonStrategy {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs (Logcat).
     */
    private static final String TAG = "ButtonAppDrawer";

    /**
     * Type d'identification du bouton AppDrawer"
     */
    public static final String TYPE = "app_drawer";

    /**
     * Gère le clic simple en lançant l'activité du tiroir d'applications.
     *
     * @param context Le contexte Android courant.
     */
    @Override
    public void onClick(Context context) {
        try {
            Intent intent = new Intent(context, AppDrawerActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            CarLog.e(TAG, "Failed to launch AppDrawerActivity", e);
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