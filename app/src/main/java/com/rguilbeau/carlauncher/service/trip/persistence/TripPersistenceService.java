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
import com.rguilbeau.carlauncher.service.telemetry.TelemetryService;
import com.rguilbeau.carlauncher.service.trip.TripListener;
import com.rguilbeau.carlauncher.service.trip.TripService;
import com.rguilbeau.carlauncher.service.trip.TripStats;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Service d'arrière-plan chargé de persister les statistiques de trajet en base de données.
 * <p>
 * Enregistre les statistiques "full" (jamais affectées par un reset manuel de l'utilisateur) via
 * {@link TripDailyRepository}, selon trois déclencheurs complémentaires : une sauvegarde immédiate
 * à l'arrêt du véhicule (front descendant vers 0 km/h) puis à chaque mise à jour des statistiques
 * tant qu'il reste à l'arrêt (voir {@link #onSpeedChanged} et {@link #onTripUpdated}), un
 * flush immédiat à la coupure du contact, et une sauvegarde périodique toutes les minutes tant que
 * le véhicule est en mouvement (voir {@link #periodicSave}) — filet de sécurité couvrant les longs
 * trajets sans arrêt.
 * </p>
 */
public class TripPersistenceService extends Service implements TripListener {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "TripPersistenceService";

    /**
     * Intervalle entre deux sauvegardes périodiques pendant la conduite.
     */
    private static final long SAVE_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);

    /**
     * Référence vers le service de trajet une fois la connexion établie.
     */
    private TripService tripService;

    /**
     * Référence ves le service diffusant les informations du véhicule (contact, vitesse...)
     */
    private TelemetryService telemetryService;

    /**
     * Indicateur d'état précisant si le service trip est actuellement attaché au service de trajet.
     */
    private boolean isTripServiceBound = false;

    /**
     * Indicateur d'état précisant si le service telemetry est actuellement attaché au service de trajet.
     */
    private boolean isTelemetryServiceBound = false;

    /**
     * Instances stables des observateurs, conservées pour pouvoir se désabonner via
     * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.Property#unbind} (une
     * référence de méthode réévaluée à chaque appel ne le permettrait pas, voir sa doc). Rejoués
     * sur {@link #handler} (thread principal), déjà utilisé par {@link #periodicSave}.
     */
    private final Consumer<Double> speedObserver = this::onSpeedChanged;
    private final Consumer<Boolean> contactOnObserver = this::onContactOnChanged;


    /**
     * Dernière vitesse connue, utilisée pour détecter le front descendant vers 0 km/h.
     */
    private int lastSpeedKmH = -1;

    /**
     * Indique si le véhicule est actuellement considéré en mouvement (vitesse non nulle à la
     * dernière télémétrie reçue). Utilisé par {@link #onTripUpdated} pour ne sauvegarder les
     * statistiques "full" que pendant les phases d'arrêt.
     */
    private boolean carIsDriving = false;

    /**
     * Planificateur de la sauvegarde périodique.
     */
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Tâche répétée sauvegardant les statistiques "full" toutes les {@link #SAVE_INTERVAL_MS},
     * uniquement pendant que le véhicule roule ({@link #carIsDriving}) : à l'arrêt, la persistance
     * est déjà assurée en temps réel par {@link #onTripUpdated}. Démarrée en continu dès
     * {@link #onCreate()} et arrêtée uniquement à la destruction du service ; elle se contente de
     * ne rien faire aux ticks survenant pendant les phases d'arrêt.
     */
    private final Runnable periodicSave = new Runnable() {
        @Override
        public void run() {
            if (carIsDriving) {
                persistCurrentStats();
            }
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
            tripService.addListener(TripPersistenceService.this);
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
            TelemetryService.LocalBinder binder = (TelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.getData().speed.bind(speedObserver);
            telemetryService.getData().contactOn.bind(contactOnObserver);
            CarLog.d(TAG, "TripPersistenceService connected to TelemetryService.");
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
     * démarrage de la boucle de sauvegarde périodique (active uniquement pendant la conduite).
     */
    @Override
    public void onCreate() {
        super.onCreate();
        Intent intentTripService = new Intent(this, TripService.class);
        isTripServiceBound = bindService(intentTripService, tripServiceConnection, Context.BIND_AUTO_CREATE);

        Intent intentTelemetryService = new Intent(this, TelemetryService.class);
        isTelemetryServiceBound = bindService(intentTelemetryService, telemetryServiceConnection, Context.BIND_AUTO_CREATE);

        handler.postDelayed(periodicSave, SAVE_INTERVAL_MS);
    }

    /**
     * Met à jour l'état de conduite ({@link #carIsDriving}) à partir de la vitesse télémétrique, et
     * déclenche une sauvegarde immédiate dès l'arrêt du véhicule (front descendant vers 0 km/h) —
     * dès lors, la sauvegarde périodique ({@link #periodicSave}) s'interrompt au profit de la
     * sauvegarde en temps réel faite par {@link #onTripUpdated}. Rejouée sur {@link #handler}.
     *
     * @param speed La vitesse actuelle du véhicule en km/h.
     */
    private void onSpeedChanged(Double speed) {
        handler.post(() -> {
            int speedKmH = (int) Math.round(speed);

            if (speedKmH == 0 && lastSpeedKmH != 0) {
                // Front descendant : le véhicule vient de s'arrêter
                carIsDriving = false;
                persistCurrentStats();
            } else if (speedKmH != 0) {
                carIsDriving = true;
            }

            lastSpeedKmH = speedKmH;
        });
    }

    /**
     * Sauvegarde les statistiques "full" à chaque notification reçue tant que le véhicule est à
     * l'arrêt ({@link #carIsDriving} à false) ; les notifications reçues pendant la conduite sont
     * ignorées, cette période restant couverte par la sauvegarde périodique ({@link #periodicSave}).
     *
     * @param daily Les statistiques "daily" affichées à l'utilisateur, non utilisées ici.
     * @param full  Les statistiques "full" à jour du jour, à persister si le véhicule est à l'arrêt.
     */
    @Override
    public void onTripUpdated(TripStats daily, TripStats full) {
        if (!carIsDriving) {
            persist(full);
        }
    }

    /**
     * Effectue un flush immédiat des statistiques à la coupure du contact, afin de ne perdre aucune
     * donnée avant une éventuelle remise à zéro du jour suivant. Rejouée sur {@link #handler}.
     *
     * @param contactOn true si le contact est mis, false s'il est coupé.
     */
    private void onContactOnChanged(Boolean contactOn) {
        handler.post(() -> {
            if (!contactOn) {
                persistCurrentStats();
            }
        });
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
            double distanceKm = full.getDistanceMeters() / 1000.0;
            int timeMinutes = full.getDriveTimeMinutes();

            TripDailyRepository.get().update(new DailyTrip(full.getDate(), distanceKm, timeMinutes));
        } catch (Exception e) {
            CarLog.e(TAG, "Unable to persist TripDaily in database", e);
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
                if (tripService != null) {
                    tripService.removeListener(this);
                }
                unbindService(tripServiceConnection);
                isTripServiceBound = false;
            }

            if (isTelemetryServiceBound) {
                if (telemetryService != null) {
                    telemetryService.getData().speed.unbind(speedObserver);
                    telemetryService.getData().contactOn.unbind(contactOnObserver);
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
