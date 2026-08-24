package com.rguilbeau.carlauncher.component.button_strategy;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * Stratégie de comportement pour un bouton permettant de basculer la luminosité du système entre le niveau minimal et maximal.
 * <p>
 * Nécessite la permission système WRITE_SETTINGS. Si elle n'est pas accordée,
 * le premier clic redirigera l'utilisateur vers la page de configuration dédiée.
 * </p>
 *
 * @author rguilbeau
 * @version 1.0
 */
public class DayNightStrategy implements ButtonStrategy {

    /**
     * Identifiant unique du type de stratégie pour le bouton Jour/Nuit.
     */
    public static final String TYPE = "day_night";

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "BrightnessStrategy";

    /**
     * Valeur de luminosité minimale appliquée (échelle 0 à 255).
     */
    private static final int BRIGHTNESS_MIN = 0;

    /**
     * Valeur de luminosité maximale appliquée (échelle 0 à 255).
     */
    private static final int BRIGHTNESS_MAX = 255;

    /**
     * Gère le clic simple sur le bouton.
     * Vérifie la permission de modification des paramètres, passe la luminosité en mode manuel,
     * puis bascule entre le niveau minimal et maximal selon la valeur actuelle.
     *
     * @param context Le contexte Android utilisé pour accéder aux paramètres et afficher des messages Toast.
     */
    @Override
    public void onClick(Context context) {
        try {
            if (!Settings.System.canWrite(context)) {
                Toast.makeText(context, "Veuillez autoriser la modification des paramètres pour gérer la luminosité.", Toast.LENGTH_LONG).show();

                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }

            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

            int currentBrightness = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 127);

            int newBrightness = (currentBrightness > 127) ? BRIGHTNESS_MIN : BRIGHTNESS_MAX;

            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, newBrightness);

        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du basculement de la luminosité", e);
        }
    }

    /**
     * Gère le clic long sur le bouton.
     * Consomme l'événement sans effectuer d'action afin d'éviter le déclenchement intempestif du clic simple.
     *
     * @param context Le contexte Android.
     * @return true pour indiquer que l'événement a été entièrement consommé.
     */
    @Override
    public boolean onLongClick(Context context) {
        return true;
    }
}