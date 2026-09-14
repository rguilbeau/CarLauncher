package com.rguilbeau.carlauncher.service.trip;

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
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.rguilbeau.carlauncher.manager.PermissionManager;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Service d'arrière-plan gérant l'enregistrement des statistiques de trajet.
 * <p>
 * S'abonne au {@link CarTelemetryService} pour détecter l'alimentation (ACC_ON/OFF).
 * Le temps de conduite est comptabilisé via le chronomètre matériel (SystemClock.elapsedRealtime)
 * pour éviter toute corruption lors des ajustements d'horloge GPS/réseau.
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
     * Clé des préférences pour stocker la distance de référence au moment du dernier reset manuel.
     */
    private static final String KEY_DAILY_RESET_DISTANCE = "dailyResetDistance";

    /**
     * Clé des préférences pour stocker le temps de conduite de référence au moment du dernier reset manuel.
     */
    private static final String KEY_DAILY_RESET_TIME = "dailyResetTime";

    /**
     * Vitesse minimale (en km/h) issue du bus CAN nécessaire pour considérer que le véhicule se déplace.
     */
    private static final float MIN_SPEED_KMH = 4.0f;

    /**
     * Distance minimale (en mètres) requise entre deux relevés GPS successifs pour être ajoutée au total.
     */
    private static final float MIN_DISTANCE_M = 2.0f;

    /**
     * Rayon maximal d'imprécision (en mètres) toléré par le capteur GPS.
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
     * Point de repère temporel monotone (basé sur le quartz système) servant de chronomètre pour le temps de conduite.
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
     * Indicateur d'état précisant si le TripService est actuellement attaché au service de télémétrie.
     */
    private boolean isBound = false;

    /**
     * Liste des écouteurs abonnés aux statistiques de trajet.
     */
    private final List<TripListener> listeners = new ArrayList<>();

    /**
     * Interface de communication permettant aux composants liés d'interagir avec ce service.
     */
    private final IBinder binder = new LocalBinder();

    /**
     * Classe interne fournissant l'instance du service aux composants clients lors du binding.
     */
    public class LocalBinder extends Binder {
        /**
         * Retourne l'instance courante du TripService.
         *
         * @return Le service de trajet actif.
         */
        public TripService getService() {
            return TripService.this;
        }
    }

    /**
     * Ajoute un nouvel abonné à la liste de diffusion des statistiques de trajet.
     * Transmet immédiatement à ce nouvel abonné l'état actuel du contact et des statistiques.
     *
     * @param listener L'écouteur à ajouter.
     */
    public synchronized void addListener(TripListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            listener.onTripUpdated(buildDailyStats(), buildFullStats());
        }
    }

    /**
     * Retire un abonné de la liste de diffusion.
     *
     * @param listener L'écouteur à retirer.
     */
    public synchronized void removeListener(TripListener listener) {
        listeners.remove(listener);
    }

    /**
     * Notifie tous les abonnés d'une mise à jour des statistiques de trajet.
     */
    private synchronized void notifyTripUpdated() {
        TripStats daily = buildDailyStats();
        TripStats full = buildFullStats();

        for (TripListener listener : listeners) {
            listener.onTripUpdated(daily, full);
        }
    }

    /**
     * Construit l'instantané des statistiques "daily" (remises à zéro par {@link #resetDaily()}).
     *
     * @return Les statistiques affichables, offset du reset manuel déduit du total réel.
     */
    private TripStats buildDailyStats() {
        float distance = prefs.getFloat(KEY_DISTANCE, 0f) - prefs.getFloat(KEY_DAILY_RESET_DISTANCE, 0f);
        long driveTime = prefs.getLong(KEY_DRIVE_TIME, 0L) - prefs.getLong(KEY_DAILY_RESET_TIME, 0L);
        return new TripStats(Math.max(distance, 0f), Math.max(driveTime, 0L), currentDayKey());
    }

    /**
     * Construit l'instantané des statistiques "full" (total réel de la journée, non affecté par
     * le reset manuel de l'utilisateur — destiné à la persistance en base).
     *
     * @return Les statistiques complètes du jour.
     */
    private TripStats buildFullStats() {
        float distance = prefs.getFloat(KEY_DISTANCE, 0f);
        long driveTime = prefs.getLong(KEY_DRIVE_TIME, 0L);
        return new TripStats(distance, driveTime, currentDayKey());
    }

    /**
     * Réinitialise à zéro les statistiques "daily" affichées à l'utilisateur, sans affecter les
     * statistiques "full" (total réel de la journée), qui restent destinées à la persistance en base.
     */
    public void resetDaily() {
        prefs.edit()
                .putFloat(KEY_DAILY_RESET_DISTANCE, prefs.getFloat(KEY_DISTANCE, 0f))
                .putLong(KEY_DAILY_RESET_TIME, prefs.getLong(KEY_DRIVE_TIME, 0L))
                .apply();

        notifyTripUpdated();
    }

    /**
     * Récupère la clé du jour ("yyyy-MM-dd") auquel les statistiques courantes sont rattachées.
     *
     * @return La clé du jour courant.
     */
    private String currentDayKey() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        return prefs.getString(KEY_SAVED_DATE, today);
    }

    /**
     * Gère le cycle de vie de la connexion avec le service de télémétrie.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(TripService.this);
            CarLog.d(TAG, "TripService connected to CANbus.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            telemetryService = null;
        }
    };

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
            CarLog.e(TAG, "Error initializing GPS", e);
        }
    }

    /**
     * Écoute les changements d'état du contact de la voiture.
     * Filtre les doublons d'événements et initialise le chronomètre monotone.
     *
     * @param accOn true si le contact est mis, false sinon.
     */
    @Override
    public void onAccStateChanged(boolean accOn) {
        // Protection contre les déclenchements en double
        if (this.isAccOn == accOn) {
            return;
        }
        this.isAccOn = accOn;

        long wallTimeNow = System.currentTimeMillis();
        long monotonicNow = SystemClock.elapsedRealtime();

        if (accOn) {
            long lastOffTime = prefs.getLong(KEY_LAST_ACC_OFF, wallTimeNow);
            long gapMillis = wallTimeNow - lastOffTime;
            if (gapMillis < 0) {
                gapMillis = 0; // Sécurité si l'horloge système a reculé pendant la veille
            }

            checkSmartReset(wallTimeNow, gapMillis);

            lastTickTime = monotonicNow;

            CarLog.i(TAG, "Ignition on (ACC_ON) trip start");
        } else {
            if (lastTickTime > 0) {
                long deltaMillis = monotonicNow - lastTickTime;
                accumulateTime(deltaMillis);
                lastTickTime = 0;
            }

            prefs.edit().putLong(KEY_LAST_ACC_OFF, wallTimeNow).commit();

            CarLog.i(TAG, "Ignition off (ACC_OFF) trip end");
        }

        notifyTripUpdated();
    }

    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        // Vitesse utilisée uniquement en interne pour filtrer les mises à jour GPS
        // (voir onLocationChanged) : ce n'est pas une information de trajet, elle n'est
        // donc pas relayée aux TripListener.
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
     * Vérifie s'il est nécessaire de remettre les statistiques du trajet à zéro (changement de jour + 3h de pause).
     *
     * @param currentTime L'heure actuelle en millisecondes.
     * @param gapMillis   La durée écoulée depuis la dernière coupure de contact.
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
                        .putFloat(KEY_DAILY_RESET_DISTANCE, 0f)
                        .putLong(KEY_DAILY_RESET_TIME, 0L)
                        .putString(KEY_SAVED_DATE, today)
                        .apply();

                notifyTripUpdated();

                CarLog.i(TAG, "Smart Reset executed: daily data reset.");
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Smart Reset error", e);
        }
    }

    /**
     * Calcule le temps et la distance parcourue à chaque mise à jour GPS.
     *
     * @param location L'objet Location contenant les nouvelles coordonnées.
     */
    @Override
    public void onLocationChanged(@NonNull Location location) {
        try {
            long monotonicNow = SystemClock.elapsedRealtime();

            // Mise à jour continue du temps de trajet en roulant via le chronomètre matériel
            if (isAccOn && lastTickTime > 0) {
                long deltaMillis = monotonicNow - lastTickTime;
                accumulateTime(deltaMillis);
                lastTickTime = monotonicNow;
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

            notifyTripUpdated();
        } catch (Exception e) {
            CarLog.e(TAG, "Error calculating trip", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

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
            CarLog.e(TAG, "Erreur nettoyage onDestroy", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }
}