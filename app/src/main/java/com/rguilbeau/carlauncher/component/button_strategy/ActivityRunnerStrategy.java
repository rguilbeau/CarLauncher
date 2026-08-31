package com.rguilbeau.carlauncher.component.button_strategy;

import android.content.Context;
import android.content.Intent;

import com.rguilbeau.carlauncher.utils.log.CarLog;

/**
 * Stratégie de comportement pour les boutons qui lance une activité
 *
 * @author rguilbeau
 * @version 1.0
 */
public class ActivityRunnerStrategy implements ButtonStrategy {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs (Logcat).
     */
    private static final String TAG = "ButtonRunActivity";

    /**
     * Type d'identification du bouton AppDrawer"
     */
    public static final String TYPE = "run_activity";

    /**
     * Le nom de l'activité à lancer
     */
    private final String activityName;

    /**
     * Constructeur de la stratégie du bouton
     *
     * @param activityName Nom de l'activité à lancer
     */
    public ActivityRunnerStrategy(String activityName) {
        this.activityName = activityName;
    }

    /**
     * Gère le clic simple en lançant l'activité du tiroir d'applications.
     *
     * @param context Le contexte Android courant.
     */
    @Override
    public void onClick(Context context) {
        try {
            Class<?> activity = resolveActivity();

            if (activity != null) {
                Intent intent = new Intent(context, activity);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } else {
                CarLog.e(TAG, "Failed to resolve activity '" + activityName + "'");
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Failed start " + activityName, e);
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

    /**
     * @return L'activity en fonction du nom
     */
    private Class<?> resolveActivity() {
        try {
            return Class.forName(activityName);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }
}