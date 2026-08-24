package com.rguilbeau.carlauncher.service;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;

import com.rguilbeau.carlauncher.manager.PermissionManager;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Service d'arrière-plan gérant l'enregistrement des statistiques de trajet.
 * <p>
 * S'abonne au {@link CarTelemetryService} pour détecter l'alimentation (ACC_ON/OFF).
 * Le temps de conduite est comptabilisé en continu dès que le contact est mis,
 * et la distance est filtrée via les données CANbus pour ignorer l'imprécision à l'arrêt.
 * </p>
 */
public class TripService extends Service implements LocationListener, CarTelemetryListener {

    /**
     * Tag utilisé pour l'identification des messages de journalisation de ce service.
     */
    private static final String TAG = "TripService";

    /**
     * Nom du fichier de préférences partagées utilisé pour la sauvegarde des statistiques.
     */
    public static final String PREFS_NAME = "CarLauncherPrefs";

    /**
     * Clé des préférences pour stocker la distance totale parcourue.
     */
    public static final String KEY_DISTANCE = "distance";

    /**
     * Clé des préférences pour stocker le temps total de conduite accumulé.
     */
    public static final String KEY_DRIVE_TIME = "driveTime";

    /**
     * Clé des préférences pour stocker la date d'enregistrement du trajet, servant au reset journalier.
     */
    public static final String KEY_SAVED_DATE = "savedDate";

    /**
     * Clé des préférences pour stocker l'horodatage précis de la dernière coupure de contact.
     */
    public static final String KEY_LAST_ACC_OFF = "lastAccOffTime";

    /**
     * Vitesse minimale (en km/h) issue du bus CAN nécessaire pour considérer que le véhicule se déplace.
     * Permet d'ignorer les dérives du signal GPS lorsque le véhicule est à l'arrêt.
     */
    private static final float MIN_SPEED_KMH = 4.0f;

    /**
     * Distance minimale (en mètres) requise entre deux relevés GPS successifs pour être ajoutée au total.
     */
    private static final float MIN_DISTANCE_M = 2.0f;

    /**
     * Rayon maximal d'imprécision (en mètres) toléré par le capteur GPS.
     * Au-delà de cette valeur, la position est ignorée pour ne pas fausser le calcul.
     */
    private static final float MAX_ACCURACY_M = 20.0f;

    /**
     * Gestionnaire des préférences pour l'écriture et la lecture persistante des données du trajet.
     */
    private SharedPreferences prefs;

    /**
     * Gestionnaire système Android fournissant les mises à jour de la localisation géographique.
     */
    private LocationManager locationManager;

    /**
     * Conserve en mémoire la dernière position GPS valide pour calculer la distance avec la nouvelle.
     */
    private Location lastLocation = null;

    /**
     * Point de repère temporel (horodatage en millisecondes) servant de chronomètre pour le temps de conduite.
     */
    private long lastTickTime = 0L;

    /**
     * Dernière vitesse connue transmise par le bus CAN, utilisée pour valider le mouvement réel.
     */
    private float currentSpeedKmH = 0f;

    /**
     * État actuel de l'alimentation du véhicule (true = contact mis, false = contact coupé).
     */
    private boolean isAccOn = false;

    /**
     * Référence vers le service central de télémétrie de la voiture.
     */
    private CarTelemetryService telemetryService;

    /**
     * Indicateur d'état précisant si le TripService est actuellement attaché (bind) au service de télémétrie.
     */
    private boolean isBound = false;

    /**
     * Gère le cycle de vie de la connexion avec le service de télémétrie.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(TripService.this);
            Log.d(TAG, "TripService connecté au CANbus.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            telemetryService = null;
        }
    };

    /**
     * Initialise le service lors de sa création.
     * Prépare le gestionnaire de préférences, tente la connexion au service CANbus,
     * et s'abonne aux mises à jour GPS si les permissions sont accordées.
     */
    @SuppressLint("MissingPermission")
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Intent intent = new Intent(this, CarTelemetryService.class);
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null && PermissionManager.hasLocationPermission(this)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur init GPS dans TripService", e);
        }
    }

    /**
     * Écoute les changements d'état du contact de la voiture.
     * Démarre le comptage du temps si le contact est mis et applique un potentiel reset journalier.
     * Arrête le chronomètre si le contact est coupé.
     *
     * @param accOn true si le contact est mis, false sinon.
     */
    @Override
    public void onAccStateChanged(boolean accOn) {
        this.isAccOn = accOn;
        long now = System.currentTimeMillis();

        if (accOn) {
            Log.i(TAG, "▶️ DÉBUT DU TRAJET : Contact allumé.");

            long lastOffTime = prefs.getLong(KEY_LAST_ACC_OFF, now);
            checkSmartReset(now, now - lastOffTime);

            lastTickTime = now;
        } else {
            Log.i(TAG, "⏹️ FIN DU TRAJET : Contact coupé.");

            if (lastTickTime > 0) {
                accumulateTime(now - lastTickTime);
                lastTickTime = 0;
            }

            prefs.edit().putLong(KEY_LAST_ACC_OFF, now).apply();
        }
    }

    /**
     * Reçoit et stocke la vitesse lue depuis le réseau CAN de la voiture.
     *
     * @param speed La vitesse du véhicule en km/h.
     * @param rpm   Le régime moteur (non utilisé ici, mais requis par l'interface).
     */
    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        this.currentSpeedKmH = speed;
    }

    /**
     * Ajoute une portion de temps écoulé au temps total de conduite sauvegardé.
     *
     * @param deltaMillis Le nombre de millisecondes à rajouter.
     */
    private void accumulateTime(long deltaMillis) {
        if (deltaMillis > 0) {
            long currentDriveTime = prefs.getLong(KEY_DRIVE_TIME, 0L) + deltaMillis;
            prefs.edit().putLong(KEY_DRIVE_TIME, currentDriveTime).apply();
        }
    }

    /**
     * Vérifie s'il est nécessaire de remettre les statistiques du trajet à zéro.
     * La remise à zéro s'effectue si un nouveau jour est détecté ET que le moteur
     * a été coupé depuis plus de 3 heures.
     *
     * @param currentTime L'heure actuelle en millisecondes.
     * @param gapMillis   La durée écoulée (en millisecondes) depuis la dernière coupure de contact.
     */
    private void checkSmartReset(long currentTime, long gapMillis) {
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(currentTime));
            String savedDate = prefs.getString(KEY_SAVED_DATE, today);

            long offDurationHours = gapMillis / (1000 * 60 * 60);

            if (!today.equals(savedDate) && offDurationHours >= 3) {
                prefs.edit()
                        .putFloat(KEY_DISTANCE, 0f)
                        .putLong(KEY_DRIVE_TIME, 0L)
                        .putString(KEY_SAVED_DATE, today)
                        .apply();

                Log.i(TAG, "♻️ Smart Reset exécuté : données journalières réinitialisées.");
            } else {
                prefs.edit().putString(KEY_SAVED_DATE, today).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur Smart Reset", e);
        }
    }

    /**
     * Appelé par le système Android à chaque nouvelle position GPS reçue.
     * Gère la mise à jour incrémentale du temps de trajet et le calcul strict
     * de la distance si le véhicule est en mouvement et la position précise.
     *
     * @param location L'objet Location contenant les nouvelles coordonnées.
     */
    @Override
    public void onLocationChanged(@NonNull Location location) {
        try {
            long now = System.currentTimeMillis();

            // Mise à jour continue du temps de trajet en roulant
            if (isAccOn && lastTickTime > 0) {
                accumulateTime(now - lastTickTime);
                lastTickTime = now;
            }

            // Filtrage des positions GPS considérées comme trop imprécises
            if (!location.hasAccuracy() || location.getAccuracy() > MAX_ACCURACY_M) return;

            // Calcul et accumulation de la distance validée
            if (lastLocation != null) {
                float distance = lastLocation.distanceTo(location);

                if (currentSpeedKmH >= MIN_SPEED_KMH && distance > MIN_DISTANCE_M) {
                    float totalDistance = prefs.getFloat(KEY_DISTANCE, 0f) + distance;
                    prefs.edit().putFloat(KEY_DISTANCE, totalDistance).apply();
                    lastLocation = location;
                }
            } else {
                lastLocation = location;
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur calcul trajet", e);
        }
    }

    /**
     * Détermine le comportement du service s'il est tué par le système.
     *
     * @return START_STICKY pour demander au système de recréer le service dès que possible.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Nettoie les ressources lors de la destruction du service.
     * Se déconnecte du service de télémétrie et arrête les mises à jour GPS.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (isBound) {
                if (telemetryService != null) {
                    telemetryService.removeListener(this);
                }
                unbindService(serviceConnection);
                isBound = false;
            }

            if (locationManager != null) {
                locationManager.removeUpdates(this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur nettoyage onDestroy", e);
        }
    }

    /**
     * Méthode obligatoire pour un Service Android, non utilisée dans ce contexte de service démarré (Started Service).
     *
     * @return null car le binding direct par d'autres composants n'est pas autorisé.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Invoquée lors d'un changement de statut du fournisseur GPS. Laissée vide volontairement.
     */
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    /**
     * Invoquée lorsque le fournisseur GPS est activé par l'utilisateur. Laissée vide volontairement.
     */
    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    /**
     * Invoquée lorsque le fournisseur GPS est désactivé par l'utilisateur. Laissée vide volontairement.
     */
    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }
}