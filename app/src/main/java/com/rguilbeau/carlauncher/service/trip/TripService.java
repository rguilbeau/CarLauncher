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
 * Le temps de conduite est comptabilisé par minutes entières via le chronomètre matériel
 * (SystemClock.elapsedRealtime), pour éviter toute corruption lors des ajustements d'horloge
 * GPS/réseau et pour limiter la fréquence de notification des {@link TripListener} abonnés (voir
 * {@link #accumulateElapsedTime}).
 * </p>
 */
public class TripService extends Service implements LocationListener, CarTelemetryListener {

    /**
     * Tag utilisé pour l'identification des messages de journalisation de ce service.
     */
    private static final String TAG = "TripService";
    /**
     * Nom du fichier de préférences partagées utilisé pour la sauvegarde des informations permettant le smart reset.
     */
    private static final String PREFS_NAME = "CarLauncherPrefs";
    /**
     * Clé des préférences pour stocker l'horodatage précis de la dernière coupure de contact.
     */
    public static final String KEY_LAST_ACC_OFF = "lastAccOffTime";
    /**
     * Vitesse minimale (en km/h) issue du bus CAN nécessaire pour considérer que le véhicule se déplace.
     */
    private static final float MIN_SPEED_KMH = 2.0f;

    /**
     * Distance minimale (en mètres) requise entre deux relevés GPS successifs pour être ajoutée au total.
     */
    private static final float MIN_DISTANCE_M = 2.0f;

    /**
     * Rayon maximal d'imprécision (en mètres) toléré par le capteur GPS.
     */
    private static final float MAX_ACCURACY_M = 20.0f;
    /**
     * La clé (nom) de l'instantané de statistique de trajet visible (avec le reset manuel pris en compte).
     */
    private static final String DAILY_STATS_KEY = "dailyStats";
    /**
     * La clé (nom) de l'instantané de statistique de trajet complet de la journée (sans le reset manuel pris en compte).
     */
    private static final String DAILY_STATS_FULL_KEY = "fullDailyStats";
    /**
     * L'instantané de statistique de trajet visible (avec le reset manuel pris en compte).
     */
    private TripStats dailyTrip;
    /**
     * L'instantané de statistique de trajet complet de la journée (sans le reset manuel pris en compte).
     */
    private TripStats fullDailyTrip;
    /**
     * Gestionnaire système Android fournissant les mises à jour de la localisation géographique.
     */
    private LocationManager locationManager;

    /**
     * Conserve en mémoire la dernière position GPS valide pour calculer la distance avec la nouvelle.
     */
    private Location lastLocation = null;
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
     * Gère le cycle de vie de la connexion avec le service de télémétrie.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        /**
         * Récupère l'instance du service de télémétrie et s'y abonne.
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(TripService.this);
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
     * Initialise le service : ouvre les préférences persistantes, se lie au service de télémétrie
     * et démarre les mises à jour GPS si la permission de localisation est accordée.
     */
    @SuppressLint("MissingPermission")
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        Intent intent = new Intent(this, CarTelemetryService.class);
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        dailyTrip = TripStats.load(getApplicationContext(), DAILY_STATS_KEY);
        fullDailyTrip = TripStats.load(getApplicationContext(), DAILY_STATS_FULL_KEY);

        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null && PermissionManager.hasLocationPermission(this)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error initializing GPS", e);
        }

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
            checkSmartReset();
            lastTickTime = monotonicNow;

            CarLog.i(TAG, "Ignition on (ACC_ON) trip start");
        } else {
            accumulateElapsedTime(monotonicNow);
            lastTickTime = 0;

            prefs.edit().putLong(KEY_LAST_ACC_OFF, wallTimeNow).commit();

            CarLog.i(TAG, "Ignition off (ACC_OFF) trip end");
        }

        notifyTripUpdated();
    }

    /**
     * Met en cache la vitesse courante, utilisée uniquement en interne pour filtrer les mises à jour
     * GPS (voir {@link #onLocationChanged}) : ce n'est pas une information de trajet, elle n'est
     * donc pas relayée aux {@link TripListener}.
     *
     * @param speed La vitesse actuelle du véhicule en km/h.
     * @param rpm   Le régime moteur actuel, non utilisé par ce service.
     */
    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        this.currentSpeedKmH = speed;
    }

    /**
     * Vérifie s'il est nécessaire de remettre les statistiques du trajet à zéro (changement de jour + 3h de pause).
     */
    private void checkSmartReset() {
        try {
            long wallTimeNow = System.currentTimeMillis();
            long lastOffTime = prefs.getLong(KEY_LAST_ACC_OFF, wallTimeNow);
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
     * Calcule le temps et la distance parcourue à chaque mise à jour GPS. Les abonnés ne sont
     * notifiés que si l'une de ces deux valeurs a effectivement changé, pas à chaque position GPS
     * reçue (le GPS remonte une position par seconde environ, y compris à l'arrêt).
     *
     * @param location L'objet Location contenant les nouvelles coordonnées.
     */
    @Override
    public void onLocationChanged(@NonNull Location location) {
        try {
            long monotonicNow = SystemClock.elapsedRealtime();
            boolean changed = false;

            // Mise à jour du temps de trajet (par minutes entières) en roulant, via le chronomètre matériel
            if (isAccOn) {
                changed |= accumulateElapsedTime(monotonicNow);
            }

            // Filtrage des positions GPS considérées comme trop imprécises
            if (!location.hasAccuracy() || location.getAccuracy() > MAX_ACCURACY_M) return;

            // Calcul et accumulation de la distance validée
            if (lastLocation != null) {
                float distance = lastLocation.distanceTo(location);

                if (currentSpeedKmH >= MIN_SPEED_KMH && distance > MIN_DISTANCE_M) {
                    changed |= dailyTrip.accumulateDistance(distance);
                    changed |= fullDailyTrip.accumulateDistance(distance);
                    lastLocation = location;
                }
            } else {
                lastLocation = location;
            }

            if (changed) {
                notifyTripUpdated();
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error calculating trip", e);
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
     * Libère les ressources à l'arrêt du service : désabonnement du service de télémétrie
     * et arrêt des mises à jour GPS.
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

    /**
     * Non utilisé : le statut du fournisseur GPS n'a pas d'impact sur le calcul du trajet.
     */
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    /**
     * Non utilisé : l'activation du fournisseur GPS n'a pas d'impact sur le calcul du trajet.
     */
    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    /**
     * Non utilisé : la désactivation du fournisseur GPS n'a pas d'impact sur le calcul du trajet.
     */
    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }
}