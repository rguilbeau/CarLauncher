package com.rguilbeau.carlauncher.service.trip;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.rguilbeau.carlauncher.service.telemetry.TelemetryService;
import com.rguilbeau.carlauncher.utils.prefskey.PerfsKey;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Service d'arrière-plan gérant l'enregistrement des statistiques de trajet.
 * <p>
 * S'abonne au {@link TelemetryService} pour l'état du contact (durée de conduite) et le
 * kilométrage (distance parcourue, voir {@link #onOdometerChanged}). Le temps de conduite est
 * comptabilisé par minutes entières via le chronomètre matériel
 * (SystemClock.elapsedRealtime), pour éviter toute corruption lors des ajustements d'horloge
 * réseau et pour limiter la fréquence de notification des {@link TripListener} abonnés (voir
 * {@link #accumulateElapsedTime}).
 * </p>
 */
public class TripService extends Service {

    /**
     * Tag utilisé pour l'identification des messages de journalisation de ce service.
     */
    private static final String TAG = "TripService";

    /**
     * L'instantané de statistique de trajet visible (avec le reset manuel pris en compte).
     */
    private TripStats dailyTrip;
    /**
     * L'instantané de statistique de trajet complet de la journée (sans le reset manuel pris en compte).
     */
    private TripStats fullDailyTrip;

    /**
     * Les shared preferences pour sauvegarder les informations permettant le smart reset
     */
    private SharedPreferences prefs;

    /**
     * Point de repère temporel monotone (basé sur le quartz système) servant de chronomètre pour le
     * temps de conduite. N'avance que par minutes entières consommées (voir
     * {@link #accumulateElapsedTime}) : le reliquat sous la minute reste implicitement représenté
     * par l'écart entre {@link SystemClock#elapsedRealtime()} et cette valeur. Vaut 0 tant qu'aucun
     * chronométrage n'est en cours (contact coupé).
     */
    private long lastTickTime = 0L;

    /**
     * État actuel de l'alimentation du véhicule (true = contact mis, false = contact coupé).
     */
    private boolean isAccOn = false;

    /**
     * Dernier kilométrage connu de l'odomètre, pour calculer la distance parcourue avec le
     * suivant. Vaut -1 tant qu'aucune valeur n'a encore été reçue (voir {@link #onOdometerChanged}) :
     * la toute première valeur reçue n'est jamais traduite en distance, elle ne sert qu'à amorcer
     * ce repère.
     */
    private long lastOdometerKm = -1;

    /**
     * Référence vers le service central de télémétrie de la voiture.
     */
    private TelemetryService telemetryService;

    /**
     * Indicateur d'état précisant si le TripService est actuellement attaché au service de télémétrie.
     */
    private boolean isBound = false;

    /**
     * Rejoue les observateurs {@code Property} sur le thread principal, pour garder tout l'état
     * mutable de ce service sur un seul et même thread — voir la doc de
     * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.Property} sur le thread
     * d'appel des observateurs.
     */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /**
     * Instances stables des observateurs, conservées pour pouvoir se désabonner via
     * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.Property#unbind} (une
     * référence de méthode réévaluée à chaque appel ne le permettrait pas, voir sa doc).
     */
    private final Consumer<Boolean> contactOnObserver = this::onContactOnChanged;
    private final Consumer<Long> odometerObserver = this::onOdometerChanged;

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
     * Gère le cycle de vie de la connexion avec le service de télémétrie.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        /**
         * Récupère l'instance du service de télémétrie et s'y abonne.
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TelemetryService.LocalBinder binder = (TelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.getData().contactOn.bind(contactOnObserver);
            telemetryService.getData().odometer.bind(odometerObserver);
            CarLog.d(TAG, "TripService connected to CANbus.");
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
     * Initialise le service : ouvre les préférences persistantes et se lie au service de
     * télémétrie.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PerfsKey.getPrefsName(), MODE_PRIVATE);

        Intent intent = new Intent(this, TelemetryService.class);
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        dailyTrip = TripStats.load(getApplicationContext(), PerfsKey.TripService.getDailyStats());
        fullDailyTrip = TripStats.load(getApplicationContext(), PerfsKey.TripService.getDailyStatsFull());

        checkSmartReset();
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
            listener.onTripUpdated(dailyTrip, fullDailyTrip);
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
        for (TripListener listener : listeners) {
            listener.onTripUpdated(dailyTrip, fullDailyTrip);
        }
    }

    /**
     * Lit de manière synchrone les statistiques "full" courantes (total réel de la journée, non
     * affecté par le reset manuel de l'utilisateur). Contrairement au flux poussé par
     * {@link TripListener#onTripUpdated}, cet accès direct ne dépend d'aucun ordre d'abonnement
     * entre services : il permet à un appelant (ex: {@link com.rguilbeau.carlauncher.service.trip.persistence.TripPersistenceService})
     * de récupérer la valeur exacte au moment précis où il en a besoin (ex: coupure du contact).
     *
     * @return Les statistiques complètes et à jour de la journée.
     */
    public synchronized TripStats getFullStats() {
        return fullDailyTrip;
    }

    /**
     * Réinitialise à zéro les statistiques "daily" affichées à l'utilisateur, sans affecter les
     * statistiques "full" (total réel de la journée), qui restent destinées à la persistance en base.
     */
    public void resetDaily() {
        dailyTrip.reset();
        notifyTripUpdated();
    }

    /**
     * Écoute les changements d'état du contact de la voiture.
     * Filtre les doublons d'événements et initialise le chronomètre monotone. Rejouée sur
     * {@link #mainHandler} : tout le reste de l'état mutable de ce service n'est touché que
     * depuis le thread principal (voir {@link #onOdometerChanged}).
     *
     * @param contactOn true si le contact est mis, false sinon.
     */
    private void onContactOnChanged(Boolean contactOn) {
        mainHandler.post(() -> {
            // Protection contre les déclenchements en double
            if (this.isAccOn == contactOn) {
                return;
            }
            this.isAccOn = contactOn;

            long wallTimeNow = System.currentTimeMillis();
            long monotonicNow = SystemClock.elapsedRealtime();

            if (contactOn) {
                checkSmartReset();
                lastTickTime = monotonicNow;

                CarLog.i(TAG, "Ignition on (contact) trip start");
            } else {
                accumulateElapsedTime(monotonicNow);
                lastTickTime = 0;

                prefs.edit().putLong(PerfsKey.TripService.getLastAccOff(), wallTimeNow).commit();

                CarLog.i(TAG, "Ignition off (contact) trip end");
            }

            notifyTripUpdated();
        });
    }

    /**
     * Vérifie s'il est nécessaire de remettre les statistiques du trajet à zéro (changement de jour + 3h de pause).
     */
    private void checkSmartReset() {
        try {
            long wallTimeNow = System.currentTimeMillis();
            long lastOffTime = prefs.getLong(PerfsKey.TripService.getLastAccOff(), wallTimeNow);
            long gapMillis = wallTimeNow - lastOffTime;
            if (gapMillis < 0) {
                gapMillis = 0; // Sécurité si l'horloge système a reculé pendant la veille
            }

            String todayStr = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date(wallTimeNow));

            long offDurationHours = gapMillis / (1000 * 60 * 60);

            if (!todayStr.equals(dailyTrip.getDayStr()) && offDurationHours >= 3) {
                dailyTrip.reset();
                fullDailyTrip.reset();

                notifyTripUpdated();

                CarLog.i(TAG, "Smart Reset executed: daily data reset.");
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Smart Reset error", e);
        }
    }

    /**
     * Convertit le temps écoulé depuis {@link #lastTickTime} en minutes entières et les accumule
     * dans {@link #dailyTrip} et {@link #fullDailyTrip}. {@link #lastTickTime} n'avance que du
     * nombre de minutes effectivement consommées, afin de conserver le reliquat sous la minute pour
     * le prochain appel plutôt que de le perdre à chaque tick.
     *
     * @param monotonicNow L'horodatage monotone courant ({@link SystemClock#elapsedRealtime()}).
     * @return true si au moins une minute a été accumulée, false sinon.
     */
    private boolean accumulateElapsedTime(long monotonicNow) {
        if (lastTickTime <= 0) {
            return false;
        }

        long minutesElapsed = (monotonicNow - lastTickTime) / 60_000L;
        if (minutesElapsed <= 0) {
            return false;
        }

        boolean changed = false;
        changed |= dailyTrip.accumulateTime((int) minutesElapsed);
        changed |= fullDailyTrip.accumulateTime((int) minutesElapsed);
        lastTickTime += minutesElapsed * 60_000L;
        return changed;
    }

    /**
     * Calcule le temps et la distance parcourue à chaque mise à jour de l'odomètre (trame CAN
     * 0F6, résolution du kilomètre entier). Sert aussi de tick périodique pour
     * {@link #accumulateElapsedTime}. Les abonnés ne sont notifiés que si l'une de ces deux
     * valeurs a effectivement changé.
     * <p>
     * La toute première valeur reçue (après {@link #lastOdometerKm} = -1) amorce simplement le
     * repère, sans compter de distance — sinon le kilométrage total du véhicule serait ajouté
     * d'un coup au trajet du jour. Un écart négatif (odomètre remis à zéro) est ignoré plutôt que
     * soustrait, mais {@link #lastOdometerKm} est quand même resynchronisé sur la nouvelle valeur.
     * </p>
     *
     * @param odometerKm Le kilométrage total courant du véhicule, en kilomètres.
     */
    private void onOdometerChanged(Long odometerKm) {
        mainHandler.post(() -> {
            try {
                boolean changed = false;

                // Mise à jour du temps de trajet (par minutes entières) en roulant, via le chronomètre matériel
                if (isAccOn) {
                    changed |= accumulateElapsedTime(SystemClock.elapsedRealtime());
                }

                if (isAccOn && lastOdometerKm >= 0) {
                    long deltaKm = odometerKm - lastOdometerKm;
                    if (deltaKm > 0) {
                        float deltaMeters = deltaKm * 1000f;
                        changed |= dailyTrip.accumulateDistance(deltaMeters);
                        changed |= fullDailyTrip.accumulateDistance(deltaMeters);
                    }
                }
                lastOdometerKm = odometerKm;

                if (changed) {
                    notifyTripUpdated();
                }
            } catch (Exception e) {
                CarLog.e(TAG, "Error calculating trip", e);
            }
        });
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
     * Libère les ressources à l'arrêt du service : désabonnement du service de télémétrie.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (isBound) {
                if (telemetryService != null) {
                    telemetryService.getData().contactOn.unbind(contactOnObserver);
                    telemetryService.getData().odometer.unbind(odometerObserver);
                }
                unbindService(serviceConnection);
                isBound = false;
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur nettoyage onDestroy", e);
        }
    }

    /**
     * Fournit le binder permettant aux composants clients de se lier à ce service.
     *
     * @param intent L'intention utilisée pour se lier.
     * @return Le {@link LocalBinder} de ce service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
