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

    private static final String TAG = "TripService";

    public static final String PREFS_NAME = "CarLauncherPrefs";
    public static final String KEY_DISTANCE = "distance";
    public static final String KEY_DRIVE_TIME = "driveTime";
    public static final String KEY_SAVED_DATE = "savedDate";
    public static final String KEY_LAST_ACC_OFF = "lastAccOffTime";

    private static final float MIN_SPEED_KMH = 4.0f;
    private static final float MIN_DISTANCE_M = 2.0f;
    private static final float MAX_ACCURACY_M = 20.0f;

    private SharedPreferences prefs;
    private LocationManager locationManager;
    private Location lastLocation = null;

    private long lastTickTime = 0L; // Sert de point de repère pour le chrono
    private float currentSpeedKmH = 0f;
    private boolean isAccOn = false;

    // --- Connexion au CarTelemetryService ---
    private CarTelemetryService telemetryService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(TripService.this);
            isBound = true;
            Log.d(TAG, "TripService connecté au CANbus.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            telemetryService = null;
        }
    };

    @SuppressLint("MissingPermission")
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // Liaison avec le service de télémétrie de la voiture
        Intent intent = new Intent(this, CarTelemetryService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        // Initialisation du GPS
        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null && PermissionManager.hasLocationPermission(this)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur init GPS dans TripService", e);
        }
    }

    // --- Implémentation de CarEventListener ---

    @Override
    public void onAccStateChanged(boolean accOn) {
        this.isAccOn = accOn;
        long now = System.currentTimeMillis();

        if (accOn) {
            Log.i(TAG, "▶️ DÉBUT DU TRAJET : Contact allumé.");

            // On vérifie s'il faut remettre à zéro (changement de jour)
            long lastOffTime = prefs.getLong(KEY_LAST_ACC_OFF, now);
            checkSmartReset(now, now - lastOffTime);

            // On démarre le chrono
            lastTickTime = now;
        } else {
            Log.i(TAG, "⏹️ FIN DU TRAJET : Contact coupé.");

            // On ajoute le temps écoulé depuis le dernier calcul
            if (lastTickTime > 0) {
                accumulateTime(now - lastTickTime);
                lastTickTime = 0; // On arrête le chrono
            }

            prefs.edit().putLong(KEY_LAST_ACC_OFF, now).apply();
        }
    }

    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        // Vitesse réelle utilisée pour savoir si on roule (pour la distance)
        this.currentSpeedKmH = speed;
    }

    // --- Logique du trajet ---

    private void accumulateTime(long deltaMillis) {
        if (deltaMillis > 0) {
            long currentDriveTime = prefs.getLong(KEY_DRIVE_TIME, 0L) + deltaMillis;
            prefs.edit().putLong(KEY_DRIVE_TIME, currentDriveTime).apply();
        }
    }

    private void checkSmartReset(long currentTime, long gapMillis) {
        try {
            String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(currentTime));
            String savedDate = prefs.getString(KEY_SAVED_DATE, today);

            long offDurationHours = gapMillis / (1000 * 60 * 60);

            // Reset si nouveau jour ET moteur coupé depuis plus de 3h
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

    @Override
    public void onLocationChanged(@NonNull Location location) {
        try {
            long now = System.currentTimeMillis();

            // 1. Mise à jour continue du temps de trajet
            if (isAccOn && lastTickTime > 0) {
                accumulateTime(now - lastTickTime);
                lastTickTime = now; // Nouveau point de départ
            }

            // Filtrage des positions GPS imprécises
            if (!location.hasAccuracy() || location.getAccuracy() > MAX_ACCURACY_M) return;

            // 2. Calcul de la distance
            if (lastLocation != null) {
                float distance = lastLocation.distanceTo(location);

                // On n'accumule la distance que si la voiture roule (vitesse CANbus) et dépasse le bruit GPS
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (isBound && telemetryService != null) {
                telemetryService.removeListener(this);
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

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        // OBLIGATOIRE : Laisser vide pour éviter le crash au réveil
    }

    @Override
    public void onProviderEnabled(@NonNull String provider) {
        // OBLIGATOIRE : Laisser vide pour éviter le crash au réveil
    }

    @Override
    public void onProviderDisabled(@NonNull String provider) {
        // OBLIGATOIRE : Laisser vide pour éviter le crash au réveil
    }
}