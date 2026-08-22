package com.rguilbeau.carlauncher.manager;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * Classe utilitaire centralisant la gestion et la vérification des permissions système.
 * <p>
 * Cette classe expose des méthodes statiques permettant de :
 * <ul>
 *     <li>Vérifier l'état des permissions d'exécution (Runtime Permissions) comme le GPS.</li>
 *     <li>Déclencher les requêtes de permissions auprès du système.</li>
 *     <li>Traiter les résultats des requêtes via un système de Callback.</li>
 *     <li>Vérifier et rediriger l'utilisateur vers les paramètres spéciaux (Notifications).</li>
 * </ul>
 * </p>
 *
 * @author rguilbeau
 * @version 1.0
 */
public class PermissionManager {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs et de débogage (Logcat).
     */
    private static final String TAG = "PermissionManager";

    /**
     * Code de requête unique interne au gestionnaire pour la localisation.
     */
    private static final int LOCATION_PERMISSION_CODE = 100;

    /**
     * Interface de rappel (Callback) permettant de notifier l'activité du résultat d'une demande.
     */
    public interface PermissionCallback {
        /**
         * Appelé lorsque la permission a été accordée par l'utilisateur.
         */
        void onGranted();

        /**
         * Appelé lorsque la permission a été refusée par l'utilisateur.
         */
        void onDenied();
    }

    /**
     * Vérifie si l'application possède la permission de localisation précise (GPS).
     *
     * @param context Le contexte de l'application ou de l'activité.
     * @return true si la permission est accordée, false sinon.
     */
    public static boolean hasLocationPermission(Context context) {
        if (context == null) {
            return false;
        }

        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Déclenche la boîte de dialogue système demandant la permission de localisation à l'utilisateur.
     * Le code de requête est géré en interne par cette classe.
     *
     * @param activity L'activité effectuant la demande (nécessaire pour afficher la popup).
     */
    public static void requestLocationPermission(Activity activity) {
        if (activity != null) {
            ActivityCompat.requestPermissions(
                    activity,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                    LOCATION_PERMISSION_CODE
            );
        }
    }

    /**
     * Traite le retour système des permissions et déclenche le callback approprié.
     *
     * @param requestCode  Le code de la requête renvoyé par l'activité.
     * @param grantResults Le tableau des résultats pour chaque permission demandée.
     * @param callback     L'interface de rappel à déclencher selon le succès ou l'échec.
     */
    public static void handlePermissionResult(int requestCode, @NonNull int[] grantResults, PermissionCallback callback) {
        if (requestCode == LOCATION_PERMISSION_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (callback != null) {
                    callback.onGranted();
                }
            } else {
                if (callback != null) {
                    callback.onDenied();
                }
            }
        }
    }

    /**
     * Vérifie si le service d'écoute des notifications est autorisé pour cette application.
     * Cette permission spéciale est requise pour intercepter les contrôles multimédias (Spotify, etc.).
     *
     * @param context Le contexte de l'application ou de l'activité.
     * @return true si le service est autorisé, false sinon.
     */
    public static boolean hasNotificationPermission(Context context) {
        if (context == null) {
            return false;
        }

        try {
            String pkgName = context.getPackageName();
            String flat = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");

            return flat != null && flat.contains(pkgName);
        } catch (Exception e) {
            Log.e(TAG, "Error checking notification listener permission", e);
            return false;
        }
    }

    /**
     * Redirige l'utilisateur vers la page des paramètres Android dédiée à l'accès aux notifications.
     *
     * @param context Le contexte utilisé pour lancer l'intention (Intent).
     */
    public static void openNotificationSettings(Context context) {
        if (context == null) {
            return;
        }

        try {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open Notification Listener settings", e);
        }
    }
}