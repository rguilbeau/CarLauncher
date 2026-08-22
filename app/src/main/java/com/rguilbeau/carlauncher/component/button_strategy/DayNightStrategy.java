package com.rguilbeau.carlauncher.component.button_strategy;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

/**
 * Stratégie de comportement pour un bouton permettant de basculer la luminosité du système (Min / Max).
 * <p>
 * Nécessite la permission système WRITE_SETTINGS. Si elle n'est pas accordée,
 * le premier clic redirigera l'utilisateur vers la page de configuration pour l'autoriser.
 * </p>
 *
 * @author rguilbeau
 * @version 1.0
 */
public class DayNightStrategy implements ButtonStrategy {

    /** Type d'identification du bouton DayNight" */
    public static final String TYPE = "day_night";
    private static final String TAG = "BrightnessStrategy";

    // Valeurs de luminosité (sur une échelle de 0 à 255)
    // On évite 0 pour le minimum pour ne pas rendre l'écran complètement noir et inutilisable
    private static final int BRIGHTNESS_MIN = 0;
    private static final int BRIGHTNESS_MAX = 255;

    @Override
    public void onClick(Context context) {
        try {
            // 1. Vérification de la permission de modification des paramètres système
            if (!Settings.System.canWrite(context)) {
                Toast.makeText(context, "Veuillez autoriser la modification des paramètres pour gérer la luminosité.", Toast.LENGTH_LONG).show();

                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
                intent.setData(Uri.parse("package:" + context.getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return;
            }

            // 2. Désactivation de la luminosité automatique (sinon notre réglage sera écrasé)
            Settings.System.putInt(context.getContentResolver(),
                    Settings.System.SCREEN_BRIGHTNESS_MODE,
                    Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);

            // 3. Récupération de la luminosité actuelle (par défaut 127 si introuvable)
            int currentBrightness = Settings.System.getInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 127);

            // 4. Logique de bascule (Toggle) : si on est à plus de la moitié, on passe au min, sinon au max
            int newBrightness = (currentBrightness > 127) ? BRIGHTNESS_MIN : BRIGHTNESS_MAX;

            // 5. Application de la nouvelle luminosité
            Settings.System.putInt(context.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, newBrightness);

        } catch (Exception e) {
            Log.e(TAG, "Erreur lors du basculement de la luminosité", e);
        }
    }

    @Override
    public boolean onLongClick(Context context) {
        // Le clic long ne fait rien, mais on retourne true pour dire que l'événement est consommé
        // (ça évite de déclencher un clic simple par erreur)
        return true;
    }
}