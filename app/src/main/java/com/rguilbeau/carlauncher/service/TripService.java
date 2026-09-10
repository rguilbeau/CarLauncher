package com.rguilbeau.carlauncher.service;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import com.rguilbeau.carlauncher.service.ignition.IgnitionListener;
import com.rguilbeau.carlauncher.service.ignition.IgnitionService;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Service d'arrière-plan gérant l'enregistrement des statistiques de trajet.
 * <p>
 * S'abonne à {@link IgnitionService} pour l'état du contact (ACC_ON/OFF) et à
 * {@link CarTelemetryService} pour le kilométrage total du véhicule (odomètre), lu en temps réel
 * sur le bus CAN.
 * <p>
 * La distance du trajet est calculée comme la différence entre le kilométrage total courant et un
 * kilométrage de référence ("point zéro" du trajet en cours), plutôt que par accumulation de
 * positions GPS : l'odomètre du véhicule est une source de vérité fiable, insensible à la perte de
 * signal GPS (tunnels, parkings, zones urbaines denses) et à la dérive de précision.
 * <p>
 * Ce calcul se fait en {@code double} (et non {@code float}) : au-delà de quelques dizaines de
 * milliers de km, un {@code float} (32 bits, ~7 chiffres significatifs) n'a plus la précision
 * nécessaire pour distinguer des écarts de 0,1 km. Un {@code double} (~15-17 chiffres significatifs)
 * offre une marge très largement suffisante pour n'importe quel kilométrage réaliste.
 * <p>
 * Le temps de conduite est comptabilisé via le chronomètre matériel (SystemClock.elapsedRealtime)
 * pour éviter toute corruption lors des ajustements d'horloge GPS/réseau.
 */
public class TripService extends Service implements IgnitionListener, CarTelemetryListener {

    /**
     * Tag utilisé pour l'identification des messages de journalisation de ce service.
     */
    private static final String TAG = "TripService";

    /**
     * Nom du fichier de préférences partagées utilisé pour la sauvegarde des statistiques.
     */
    public static final String PREFS_NAME = "CarLauncherPrefs";

    /**
     * Clé des préférences pour stocker la distance du trajet en cours, en mètres.
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
     * Clé des préférences pour stocker le kilométrage total (odomètre CAN) servant de point zéro
     * au trajet actuellement comptabilisé. Toute distance affichée vaut (kilométrage courant - cette
     * référence). Un composant externe (ex: {@code CardTrip}) qui souhaite forcer une remise à zéro
     * du trajet doit écrire {@link #NO_REFERENCE_MILEAGE} sur cette clé en plus de {@link #KEY_DISTANCE}.
     * <p>
     * Stockée sous forme de {@link String} (via {@code Double.toString}/{@code parseDouble}) :
     * {@link SharedPreferences} n'a pas d'équivalent {@code putDouble}/{@code getDouble} natif.
     */
    public static final String KEY_REFERENCE_MILEAGE_KM = "refMileageKm";

    /**
     * Valeur sentinelle indiquant qu'aucun kilométrage de référence n'est encore défini : le
     * prochain relevé d'odomètre reçu du bus CAN servira de nouveau point de départ (distance = 0).
     */
    public static final double NO_REFERENCE_MILEAGE = -1d;

    /**
     * Intervalle (en millisecondes) entre deux sauvegardes incrémentales du temps de conduite
     * pendant que le contact est mis, afin de limiter la perte de données en cas d'arrêt brutal
     * du service (kill système, crash) avant la prochaine coupure de contact.
     */
    private static final long DRIVE_TIME_TICK_MS = 30_000L;

    /**
     * Gestionnaire des préférences pour l'écriture et la lecture persistante des données du trajet.
     */
    private SharedPreferences prefs;

    /**
     * Point de repère temporel monotone (basé sur le quartz système) servant de chronomètre pour le temps de conduite.
     * Accédé uniquement depuis le thread principal (callbacks ACC + tick périodique).
     */
    private long lastTickTime = 0L;

    /**
     * État actuel de l'alimentation du véhicule (true = contact mis, false = contact coupé).
     * Accédé uniquement depuis le thread principal.
     */
    private boolean isAccOn = false;

    /**
     * Kilométrage de référence en mémoire (copie de {@link #KEY_REFERENCE_MILEAGE_KM}), utilisé
     * pour calculer la distance sans relire les préférences à chaque trame CAN. Accédé à la fois
     * depuis le thread Binder (trames CAN) et le thread principal (reset journalier) : accès
     * synchronisé.
     */
    private double referenceMileageKm = NO_REFERENCE_MILEAGE;

    /**
     * Dernier kilométrage total reçu du bus CAN. Permet d'ignorer les trames répétées (l'odomètre
     * ne change réellement que tous les ~100 m) et d'éviter des écritures inutiles.
     */
    private double lastMileageKm = Double.NaN;

    /**
     * Référence vers le service d'état du contact du véhicule.
     */
    private IgnitionService ignitionService;

    /**
     * Indicateur d'état précisant si le TripService est actuellement attaché au service d'ignition.
     */
    private boolean isIgnitionBound = false;

    /**
     * Référence vers le service central de télémétrie de la voiture.
     */
    private CarTelemetryService telemetryService;

    /**
     * Indicateur d'état précisant si le TripService est actuellement attaché au service de télémétrie.
     */
    private boolean isTelemetryBound = false;

    /**
     * Boucle de rappel périodique (thread principal) qui sauvegarde incrémentalement le temps de
     * conduite pendant que le contact est mis, indépendamment de toute source GPS.
     */
    private final Handler tickHandler = new Handler(Looper.getMainLooper());
    private final Runnable driveTimeTick = new Runnable() {
        @Override
        public void run() {
            if (isAccOn && lastTickTime > 0) {
                long now = SystemClock.elapsedRealtime();
                accumulateTime(now - lastTickTime);
                lastTickTime = now;
            }
            tickHandler.postDelayed(this, DRIVE_TIME_TICK_MS);
        }
    };

    /**
     * Détecte une remise à zéro du kilométrage de référence déclenchée par un autre composant
     * (ex: {@code CardTrip} lors d'un reset manuel) et recharge la valeur en mémoire en conséquence.
     * <p>
     * Si la référence vient d'être effacée (sentinelle) et que le kilométrage courant est déjà
     * connu, rebase immédiatement dessus plutôt que d'attendre la prochaine trame CAN : sans ça,
     * cette prochaine trame deviendrait elle-même le nouveau "zéro", décalant la distance affichée
     * d'un relevé.
     */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sharedPreferences, key) -> {
                if (!KEY_REFERENCE_MILEAGE_KM.equals(key)) return;

                double newReference = parseDoubleOrDefault(
                        sharedPreferences.getString(KEY_REFERENCE_MILEAGE_KM, null), NO_REFERENCE_MILEAGE);

                boolean alreadyUpToDate;
                synchronized (this) {
                    alreadyUpToDate = (referenceMileageKm == newReference);
                }
                if (alreadyUpToDate) return; // Évite de retraiter notre propre écriture de rebase ci-dessous.

                if (newReference == NO_REFERENCE_MILEAGE) {
                    double rebased = rebaseOnKnownMileageOrSentinel();
                    if (rebased >= 0d) {
                        prefs.edit()
                                .putString(KEY_REFERENCE_MILEAGE_KM, Double.toString(rebased))
                                .putFloat(KEY_DISTANCE, 0f)
                                .apply();
                    }
                } else {
                    synchronized (this) {
                        referenceMileageKm = newReference;
                        lastMileageKm = Double.NaN;
                    }
                }
            };

    /**
     * Rebase la référence sur le kilométrage courant si celui-ci est déjà connu (service de
     * télémétrie déjà connecté et ayant reçu au moins une trame), sinon retombe sur la valeur
     * sentinelle en attendant la prochaine trame CAN.
     *
     * @return Le kilométrage utilisé comme nouvelle référence, ou {@link #NO_REFERENCE_MILEAGE}.
     */
    private double rebaseOnKnownMileageOrSentinel() {
        double knownMileage = (telemetryService != null) ? telemetryService.getCurrentMileageKm() : -1d;
        double newReference = (knownMileage >= 0d) ? knownMileage : NO_REFERENCE_MILEAGE;
        synchronized (this) {
            referenceMileageKm = newReference;
            lastMileageKm = Double.NaN;
        }
        return newReference;
    }

    /**
     * Parse une chaîne en {@code double}, ou retourne une valeur par défaut si {@code null} ou
     * invalide (ex: première exécution, aucune référence encore enregistrée).
     */
    private static double parseDoubleOrDefault(String value, double defaultValue) {
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * Gère le cycle de vie de la connexion avec le service d'état du contact.
     */
    private final ServiceConnection ignitionConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IgnitionService.LocalBinder binder = (IgnitionService.LocalBinder) service;
            ignitionService = binder.getService();
            ignitionService.addListener(TripService.this);
            CarLog.d(TAG, "TripService connected to IgnitionService.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            ignitionService = null;
        }
    };

    /**
     * Gère le cycle de vie de la connexion avec le service de télémétrie.
     */
    private final ServiceConnection telemetryConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(TripService.this);
            CarLog.d(TAG, "TripService connected to CarTelemetryService.");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            telemetryService = null;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        referenceMileageKm = parseDoubleOrDefault(prefs.getString(KEY_REFERENCE_MILEAGE_KM, null), NO_REFERENCE_MILEAGE);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);

        isIgnitionBound = bindService(new Intent(this, IgnitionService.class), ignitionConnection, Context.BIND_AUTO_CREATE);
        isTelemetryBound = bindService(new Intent(this, CarTelemetryService.class), telemetryConnection, Context.BIND_AUTO_CREATE);

        tickHandler.postDelayed(driveTimeTick, DRIVE_TIME_TICK_MS);
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
    }

    /**
     * Reçoit chaque relevé de kilométrage total (odomètre) du bus CAN et met à jour la distance du
     * trajet en cours, calculée comme la différence avec le kilométrage de référence.
     * <p>
     * Appelée sur un thread du pool Binder (voir {@code CarTelemetryService}), pas le thread principal.
     *
     * @param totalKm Le kilométrage total actuel du véhicule, en kilomètres.
     */
    @Override
    public void onMileageUpdated(double totalKm) {
        if (totalKm < 0d) return;

        double distanceKm;
        boolean referenceJustEstablished;
        synchronized (this) {
            if (totalKm == lastMileageKm) {
                return; // Trame répétée : l'odomètre n'a pas encore franchi le prochain 0,1 km.
            }
            lastMileageKm = totalKm;

            referenceJustEstablished = (referenceMileageKm == NO_REFERENCE_MILEAGE);
            if (referenceJustEstablished) {
                referenceMileageKm = totalKm;
                distanceKm = 0d;
            } else {
                distanceKm = totalKm - referenceMileageKm;
                if (distanceKm < 0d) {
                    // Régression improbable (glitch de décodage, calculateur remplacé...) :
                    // on ignore la trame plutôt que de faire reculer la distance affichée.
                    CarLog.w(TAG, "Ignoring mileage regression: total=" + totalKm + " reference=" + referenceMileageKm);
                    return;
                }
            }
        }

        float distanceMeters = (float) Math.round(distanceKm * 1000d);

        SharedPreferences.Editor editor = prefs.edit().putFloat(KEY_DISTANCE, distanceMeters);
        if (referenceJustEstablished) {
            editor.putString(KEY_REFERENCE_MILEAGE_KM, Double.toString(totalKm));
        }
        editor.apply();
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
                // Rebase immédiat si le kilométrage CAN est déjà connu (service de télémétrie déjà
                // connecté), sinon on retombe sur la valeur sentinelle : la prochaine trame reçue
                // via onMileageUpdated établira le nouveau point de référence.
                double newReference = rebaseOnKnownMileageOrSentinel();

                prefs.edit()
                        .putFloat(KEY_DISTANCE, 0f)
                        .putLong(KEY_DRIVE_TIME, 0L)
                        .putString(KEY_SAVED_DATE, today)
                        .putString(KEY_REFERENCE_MILEAGE_KM, Double.toString(newReference))
                        .apply();

                CarLog.i(TAG, "Smart Reset executed: daily data reset.");
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Smart Reset error", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        tickHandler.removeCallbacks(driveTimeTick);
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        try {
            if (isIgnitionBound) {
                if (ignitionService != null) {
                    ignitionService.removeListener(this);
                }
                unbindService(ignitionConnection);
                isIgnitionBound = false;
            }
            if (isTelemetryBound) {
                if (telemetryService != null) {
                    telemetryService.removeListener(this);
                }
                unbindService(telemetryConnection);
                isTelemetryBound = false;
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
