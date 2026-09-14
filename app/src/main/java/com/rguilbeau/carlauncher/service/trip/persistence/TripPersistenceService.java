package com.rguilbeau.carlauncher.service.trip.persistence;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import com.rguilbeau.carlauncher.repository.TripDailyRepository;
import com.rguilbeau.carlauncher.repository.dto.DailyTrip;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService;
import com.rguilbeau.carlauncher.service.trip.TripListener;
import com.rguilbeau.carlauncher.service.trip.TripService;
import com.rguilbeau.carlauncher.service.trip.TripStats;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Service d'arrière-plan chargé de persister les statistiques de trajet en base de données.
 * <p>
 * S'abonne au {@link TripService} et enregistre les statistiques "full" (jamais affectées par un
 * reset manuel de l'utilisateur) à chaque retour à l'arrêt du véhicule, puis toutes les minutes
 * tant que celui-ci reste immobile, via {@link TripDailyRepository}.
 * </p>
 */
public class TripPersistenceService extends Service implements TripListener, CarTelemetryListener {

    private static final String TAG = "TripPersistenceService";

    /**
     * Intervalle entre deux sauvegardes périodiques tant que le véhicule est à l'arrêt.
     */
    private static final long SAVE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Référence vers le service de trajet une fois la connexion établie.
     */
    private TripService tripService;

    /**
     * Référence ves le service diffusant les informations du véhicule (ACC_ON/OFF, speed...)
     */
    private CarTelemetryService telemetryService;

    /**
     * Indicateur d'état précisant si le service trip est actuellement attaché au service de trajet.
     */
    private boolean isTripServiceBound = false;

    /**
     * Indicateur d'état précisant si le service telemetry est actuellement attaché au service de trajet.
     */
    private boolean isTelemetryServiceBound = false;


    /**
     * Dernière vitesse connue, utilisée pour détecter le front descendant vers 0 km/h.
     */
    private int lastSpeedKmH = -1;

    /**
     * Dernières statistiques "full" reçues, utilisées lors d'une sauvegarde (périodique ou déclenchée
     * par une coupure du contact).
     */
    private volatile TripStats lastFullStats = null;

    /**
     * Planificateur des sauvegardes périodiques tant que le véhicule reste à l'arrêt.
     */
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable periodicSave = new Runnable() {
        @Override
        public void run() {
            persist(lastFullStats);
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    /**
     * Gère le cycle de vie de la connexion avec le service de trajet.
     */
    private final ServiceConnection tripServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TripService.LocalBinder binder = (TripService.LocalBinder) service;
            tripService = binder.getService();
            tripService.addListener(TripPersistenceService.this);
            CarLog.d(TAG, "TripPersistenceService connected to TripService.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            tripService = null;
        }
    };

    /**
     * Gère le cycle de vie de la connexion avec le service de trajet.
     */
    private final ServiceConnection telemetryServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(TripPersistenceService.this);
            CarLog.d(TAG, "TripPersistenceService connected to CarTelemetryService.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            telemetryService = null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Intent intentTripService = new Intent(this, TripService.class);
        isTripServiceBound = bindService(intentTripService, tripServiceConnection, Context.BIND_AUTO_CREATE);

        Intent intentTelemetryService = new Intent(this, CarTelemetryService.class);
        isTelemetryServiceBound = bindService(intentTelemetryService, telemetryServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onTripUpdated(TripStats daily, TripStats full) {
        lastFullStats = full;
    }

    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        if (speed == 0) {
            if (lastSpeedKmH != 0) {
                // Front descendant : le véhicule vient de s'arrêter
                persist(lastFullStats);
                handler.removeCallbacks(periodicSave);
                handler.postDelayed(periodicSave, SAVE_INTERVAL_MS);
            }
        } else {
            handler.removeCallbacks(periodicSave);
        }

        lastSpeedKmH = speed;
    }

    @Override
    public void onAccStateChanged(boolean accOn) {
        if (!accOn) {
            // Flush final pour ne perdre aucune donnée avant une éventuelle remise à zéro du jour suivant
            handler.removeCallbacks(periodicSave);
            persist(lastFullStats);
        }
    }

    /**
     * Convertit les statistiques "full" en {@link DailyTrip} et déclenche l'upsert en base via
     * {@link TripDailyRepository}.
     *
     * @param full Les statistiques complètes du jour à persister.
     */
    private void persist(TripStats full) {
        if (full == null) {
            return;
        }

        try {
            Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(full.dayKey);
            double distanceKm = full.distanceMeters / 1000.0;
            int timeMinutes = (int) (full.driveTimeMillis / 60000);

            TripDailyRepository.get().update(new DailyTrip(date, distanceKm, timeMinutes));
        } catch (ParseException e) {
            CarLog.e(TAG, "Unable to parse day key: " + full.dayKey, e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(periodicSave);

        try {
            if (isTripServiceBound) {
                if (tripService != null) {
                    tripService.removeListener(this);
                }
                unbindService(tripServiceConnection);
                isTripServiceBound = false;
            }

            if (isTelemetryServiceBound) {
                if (telemetryService != null) {
                    telemetryService.removeListener(this);
                }
                unbindService(telemetryServiceConnection);
                isTelemetryServiceBound = false;
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur nettoyage onDestroy", e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
