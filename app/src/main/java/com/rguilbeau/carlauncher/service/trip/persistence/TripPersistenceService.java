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
 * Interroge {@link TripService} de manière synchrone (via {@link TripService#getFullStats()}) et
 * enregistre les statistiques "full" (jamais affectées par un reset manuel de l'utilisateur) via
 * {@link TripDailyRepository}, selon trois déclencheurs cumulés : une sauvegarde immédiate à
 * chaque retour à l'arrêt du véhicule, un flush immédiat à la coupure du contact, et une
 * sauvegarde périodique inconditionnelle toutes les minutes, qu'importe l'état du véhicule
 * (roulant ou à l'arrêt) — filet de sécurité couvrant les longs trajets sans arrêt. L'accès
 * synchrone évite toute dépendance à l'ordre d'abonnement entre services : la valeur lue est
 * toujours celle en vigueur au moment exact de la sauvegarde.
 * </p>
 */
public class TripPersistenceService extends Service implements CarTelemetryListener {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "TripPersistenceService";

    /**
     * Intervalle entre deux sauvegardes périodiques inconditionnelles (roulant ou à l'arrêt).
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
     * Planificateur de la sauvegarde périodique inconditionnelle.
     */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Tâche répétée sauvegardant les statistiques "full" toutes les {@link #SAVE_INTERVAL_MS}, en
     * continu et indépendamment de l'état du véhicule (roulant ou à l'arrêt). Démarrée une seule
     * fois dans {@link #onCreate()} et arrêtée uniquement à la destruction du service.
     */
    private final Runnable periodicSave = new Runnable() {
        @Override
        public void run() {
            persistCurrentStats();
            handler.postDelayed(this, SAVE_INTERVAL_MS);
        }
    };

    /**
     * Gère le cycle de vie de la connexion avec le service de trajet.
     */
    private final ServiceConnection tripServiceConnection = new ServiceConnection() {
        /**
         * Récupère l'instance du service de trajet et s'y abonne.
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TripService.LocalBinder binder = (TripService.LocalBinder) service;
            tripService = binder.getService();
            CarLog.d(TAG, "TripPersistenceService connected to TripService.");
        }

        /**
         * Oublie la référence au service de trajet devenue invalide.
         */
        @Override
        public void onServiceDisconnected(ComponentName name) {
            tripService = null;
        }
    };

    /**
     * Gère le cycle de vie de la connexion avec le service de trajet.
     */
    private final ServiceConnection telemetryServiceConnection = new ServiceConnection() {
        /**
         * Récupère l'instance du service de télémétrie et s'y abonne.
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(TripPersistenceService.this);
            CarLog.d(TAG, "TripPersistenceService connected to CarTelemetryService.");
        }

        /**
         * Oublie la référence au service de télémétrie devenue invalide.
         */
        @Override
        public void onServiceDisconnected(ComponentName name) {
            telemetryService = null;
        }
    };

    /**
     * Initialise le service : liaison au service de trajet et au service de télémétrie, puis
     * démarrage de la boucle de sauvegarde périodique inconditionnelle.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Intent intentTripService = new Intent(this, TripService.class);
        isTripServiceBound = bindService(intentTripService, tripServiceConnection, Context.BIND_AUTO_CREATE);

        Intent intentTelemetryService = new Intent(this, CarTelemetryService.class);
        isTelemetryServiceBound = bindService(intentTelemetryService, telemetryServiceConnection, Context.BIND_AUTO_CREATE);

        handler.postDelayed(periodicSave, SAVE_INTERVAL_MS);
    }

    /**
     * Déclenche une sauvegarde immédiate dès que le véhicule s'arrête (front descendant vers 0 km/h).
     * La sauvegarde périodique inconditionnelle ({@link #periodicSave}) continue par ailleurs de
     * tourner indépendamment de cet événement.
     *
     * @param speed La vitesse actuelle du véhicule en km/h.
     * @param rpm   Le régime moteur actuel, non utilisé ici.
     */
    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        if (speed == 0 && lastSpeedKmH != 0) {
            // Front descendant : le véhicule vient de s'arrêter
            persistCurrentStats();
        }

        lastSpeedKmH = speed;
    }

    /**
     * Effectue un flush immédiat des statistiques à la coupure du contact, afin de ne perdre aucune
     * donnée avant une éventuelle remise à zéro du jour suivant.
     *
     * @param accOn true si le contact est mis, false s'il est coupé.
     */
    @Override
    public void onAccStateChanged(boolean accOn) {
        if (!accOn) {
            persistCurrentStats();
        }
    }

    /**
     * Lit les statistiques "full" courantes directement sur {@link TripService} (accès synchrone,
     * indépendant de tout ordre d'abonnement) et déclenche leur persistance.
     */
    private void persistCurrentStats() {
        if (tripService == null) {
            CarLog.w(TAG, "TripService non connecté, sauvegarde ignorée.");
            return;
        }
        persist(tripService.getFullStats());
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

    /**
     * Demande à Android de redémarrer le service (sans réintention) s'il venait à être tué.
     *
     * @return {@link #START_STICKY}.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Libère les ressources à l'arrêt du service : sauvegarde périodique annulée et désabonnement
     * des deux services liés.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(periodicSave);

        try {
            if (isTripServiceBound) {
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

    /**
     * Aucun composant ne se lie à ce service : il n'expose pas de binder.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
