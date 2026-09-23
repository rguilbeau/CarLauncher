package com.rguilbeau.carlauncher.service.telemetry.canbus.reader.simulator;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.peugeot407.Frame0B6;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.peugeot407.Frame0F6;
import com.rguilbeau.carlauncher.service.telemetry.canbus.reader.CanReader;
import com.rguilbeau.carlauncher.utils.log.CarLog;
import com.rguilbeau.carlauncher.utils.prefskey.PerfsKey;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * {@link CanReader} de développement : n'utilise aucun matériel. Génère des trames CAN 0B6/0F6
 * synthétiques, avec le même encodage que les trames réelles de la Peugeot 407, et les fait
 * décoder par les vrais {@link Frame0B6}/{@link Frame0F6}. Exerce donc toute la chaîne
 * {@code CanBus -> Frame -> VehicleData} exactement comme le ferait {@link
 * com.rguilbeau.carlauncher.service.telemetry.canbus.reader.canable.CanableReader}, sans
 * adaptateur CANable ni véhicule branché.
 * <p>
 * Entièrement piloté par broadcast (voir plus bas) : hors {@link #start}/{@link #stop} (contrat
 * {@link CanReader}), aucune méthode n'est publique. Tant qu'aucun broadcast de routine n'a été
 * reçu, le simulateur reste inerte (contact coupé, régime et vitesse à zéro).
 * </p>
 * <p>
 * <b>Odomètre persistant</b> : contrairement au régime/à la vitesse, le kilométrage se comporte
 * comme celui d'un vrai véhicule — il ne repart jamais de zéro. Sa valeur est restaurée depuis
 * les préférences dès {@link #start}, puis sauvegardée à chaque changement (trame de routine ou
 * {@link #ACTION_SET_ODOMETER}), voir {@link #persistOdometer}. La routine 1 continue donc
 * toujours depuis le kilométrage courant plutôt que d'en prendre un en paramètre ; seul
 * {@link #ACTION_SET_ODOMETER} permet de le positionner explicitement.
 * </p>
 * <p>
 * Contrôle à distance (debug uniquement) : {@link #start} enregistre un {@link BroadcastReceiver}
 * <b>exporté</b>, pensé pour être déclenché depuis {@code adb} sans recompiler l'app :
 * <pre>{@code
 * # Démarrer la routine 1 (trajet type), avec des extras optionnels
 * adb shell am broadcast -a com.rguilbeau.carlauncher.debug.simulator.START_ROUTINE_1 \
 *     --ef cruise_speed_kmh 130 --ez loop true
 *
 * # Arrêter la routine active
 * adb shell am broadcast -a com.rguilbeau.carlauncher.debug.simulator.STOP_ROUTINE
 *
 * # Forcer une valeur de VehicleData directement (sans passer par une trame/un décodeur ; écrasé
 * # au tick suivant si une routine est active)
 * adb shell am broadcast -a com.rguilbeau.carlauncher.debug.simulator.SET_RPM --ei value 3000
 * adb shell am broadcast -a com.rguilbeau.carlauncher.debug.simulator.SET_SPEED --ef value 90
 * adb shell am broadcast -a com.rguilbeau.carlauncher.debug.simulator.SET_CONTACT_ON --ez value true
 * adb shell am broadcast -a com.rguilbeau.carlauncher.debug.simulator.SET_ODOMETER --el value 87450
 * }</pre>
 * L'export volontaire de ce receiver n'est pas un risque en production : cette classe n'y est
 * jamais instanciée (voir {@code TelemetryService}, qui choisit le reader selon
 * {@code DeviceEnvironment.isProd()}).
 * </p>
 */
public class ReaderSimulatorPeugeot407 implements CanReader {

    private static final String TAG = "ReaderSimulatorP407";

    // --- Actions et extras du contrôle à distance (voir la doc de classe) -----------------------

    private static final String ACTION_START_ROUTINE_1 = "com.rguilbeau.carlauncher.debug.simulator.START_ROUTINE_1";
    private static final String ACTION_STOP_ROUTINE = "com.rguilbeau.carlauncher.debug.simulator.STOP_ROUTINE";
    private static final String ACTION_SET_RPM = "com.rguilbeau.carlauncher.debug.simulator.SET_RPM";
    private static final String ACTION_SET_SPEED = "com.rguilbeau.carlauncher.debug.simulator.SET_SPEED";
    private static final String ACTION_SET_CONTACT_ON = "com.rguilbeau.carlauncher.debug.simulator.SET_CONTACT_ON";
    private static final String ACTION_SET_ODOMETER = "com.rguilbeau.carlauncher.debug.simulator.SET_ODOMETER";

    /** Extra {@code float}, optionnel sur {@link #ACTION_START_ROUTINE_1} : vitesse de croisière visée (km/h). */
    private static final String EXTRA_CRUISE_SPEED_KMH = "cruise_speed_kmh";

    /** Extra {@code boolean}, optionnel sur {@link #ACTION_START_ROUTINE_1} : reboucler le trajet ou non. */
    private static final String EXTRA_LOOP = "loop";

    /** Extra portant la valeur à appliquer sur les {@code ACTION_SET_*} (type selon le champ ciblé). */
    private static final String EXTRA_VALUE = "value";

    /** Valeur par défaut de {@link #EXTRA_CRUISE_SPEED_KMH} si absente du broadcast. */
    private static final float DEFAULT_CRUISE_SPEED_KMH = 110;

    /** Id de trame CAN utilisé par {@link Frame0B6} (régime moteur + vitesse). */
    private static final String FRAME_ID_RPM_SPEED = "0B6";

    /** Id de trame CAN utilisé par {@link Frame0F6} (contact + kilométrage). */
    private static final String FRAME_ID_CONTACT_ODOMETER = "0F6";

    /** Intervalle entre deux trames simulées, proche du débit réel du bus Confort. */
    private static final long TICK_INTERVAL_MS = 100;

    /** Régime moteur au ralenti (tr/min), contact mis, véhicule à l'arrêt. */
    private static final int IDLE_RPM = 800;

    // Durées des phases de la routine 1, exprimées en nombre de ticks (voir TICK_INTERVAL_MS).
    private static final long IDLE_BEFORE_TICKS = 3000 / TICK_INTERVAL_MS;
    private static final long ACCEL_TICKS = 25000 / TICK_INTERVAL_MS;
    private static final long CRUISE_TICKS = 15000 / TICK_INTERVAL_MS;
    private static final long DECEL_TICKS = 20000 / TICK_INTERVAL_MS;
    private static final long IDLE_AFTER_TICKS = 3000 / TICK_INTERVAL_MS;

    /** Durée totale d'un cycle de la routine 1 (somme des phases ci-dessus). */
    private static final long CYCLE_TICKS =
            IDLE_BEFORE_TICKS + ACCEL_TICKS + CRUISE_TICKS + DECEL_TICKS + IDLE_AFTER_TICKS;

    /**
     * Table des rapports de boîte : pour une vitesse dans [speedFromKmh, speedToKmh[, le régime
     * varie linéairement entre rpmFromRpm et rpmToRpm. Le passage d'une plage à la suivante
     * produit la chute de régime caractéristique d'un changement de rapport (voir
     * {@link #rpmForSpeed}). Valeurs approximatives, uniquement destinées à donner un rendu
     * réaliste en simulation.
     */
    private static final Gear[] GEARBOX = {
            new Gear(0, 25, IDLE_RPM, 3200),
            new Gear(25, 45, 1400, 3200),
            new Gear(45, 70, 1400, 3000),
            new Gear(70, 100, 1400, 2900),
            new Gear(100, 160, 1400, 2600),
    };

    /**
     * Exécuteur mono-thread portant le tick périodique de la routine active (voir
     * {@link #startRoutine1}).
     */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    private final Context context;

    /** Préférences dans lesquelles l'odomètre simulé est persisté (voir {@link #persistOdometer}). */
    private final SharedPreferences prefs;

    private VehicleData data;
    private Map<String, Frame> frames;

    /** Évite un double enregistrement du receiver de contrôle si {@link #start} est appelée plusieurs fois. */
    private boolean receiverRegistered;

    /**
     * Instance stable de l'observateur, conservée pour pouvoir se désabonner via
     * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.Property#unbind} (une
     * référence de méthode réévaluée à chaque appel ne le permettrait pas, voir sa doc).
     */
    private final Consumer<Long> odometerPersistObserver = this::persistOdometer;

    /** Routine actuellement planifiée sur {@link #executor}, ou {@code null} si aucune. */
    private volatile ScheduledFuture<?> activeRoutine;

    /**
     * @param context Contexte utilisé pour enregistrer le {@link BroadcastReceiver} de contrôle
     *                à distance (voir la doc de classe) et pour persister l'odomètre simulé.
     */
    public ReaderSimulatorPeugeot407(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = this.context.getSharedPreferences(PerfsKey.getPrefsName(), Context.MODE_PRIVATE);
    }

    /**
     * Mémorise les références nécessaires à l'émission de trames ({@link #dispatch}), restaure
     * l'odomètre persisté (voir {@link #persistOdometer}) et enregistre le receiver de contrôle à
     * distance. Ne lance aucune routine : le simulateur reste inerte (contact coupé) tant qu'un
     * broadcast de routine n'a pas été reçu.
     */
    @Override
    public void start(VehicleData data, Map<String, Frame> frames) {
        this.data = data;
        this.frames = frames;

        long odometerKm = prefs.getLong(PerfsKey.ReaderSimulatorPeugeot407.getOdometerKm(), 0L);
        data.odometer.set(odometerKm);
        data.odometer.bind(odometerPersistObserver);

        registerDebugControlReceiver();
        CarLog.d(TAG, "Simulateur CANable Peugeot 407 prêt (odomètre restauré à " + odometerKm + " km)");
    }

    /**
     * Persiste {@code odometerKm}, appelée à chaque changement de l'odomètre simulé — qu'il
     * vienne d'une trame de routine ({@link #emitContactOdometer}) ou de
     * {@link #ACTION_SET_ODOMETER} — pour qu'il ne reparte jamais de zéro au prochain démarrage,
     * comme le ferait un vrai odomètre.
     */
    private void persistOdometer(Long odometerKm) {
        prefs.edit().putLong(PerfsKey.ReaderSimulatorPeugeot407.getOdometerKm(), odometerKm).apply();
    }

    /**
     * Désenregistre le receiver de contrôle, stoppe la routine active et libère le thread de
     * simulation.
     */
    @Override
    public void stop() {
        if (receiverRegistered) {
            try {
                context.unregisterReceiver(debugControlReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver déjà désenregistré : sans effet, on l'ignore.
            }
            receiverRegistered = false;
        }
        data.odometer.unbind(odometerPersistObserver);
        stopRoutine();
        executor.shutdownNow();
    }

    // --- Contrôle à distance ----------------------------------------------------------------------

    /**
     * Enregistre {@link #debugControlReceiver} pour les actions listées dans la doc de classe.
     * <p>
     * Volontairement {@link Context#RECEIVER_EXPORTED} (au lieu de {@code RECEIVER_NOT_EXPORTED}
     * comme les receivers internes de {@code CanableReader}) : ce canal doit rester atteignable
     * depuis {@code adb} (UID différent de celui de l'app), qui est justement son seul usage
     * prévu (voir la doc de classe).
     * </p>
     */
    private void registerDebugControlReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_START_ROUTINE_1);
        filter.addAction(ACTION_STOP_ROUTINE);
        filter.addAction(ACTION_SET_RPM);
        filter.addAction(ACTION_SET_SPEED);
        filter.addAction(ACTION_SET_CONTACT_ON);
        filter.addAction(ACTION_SET_ODOMETER);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(debugControlReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(debugControlReceiver, filter);
        }
        receiverRegistered = true;
    }

    /**
     * Route chaque action de contrôle à distance vers la routine ou le champ de
     * {@link VehicleData} concerné. Les {@code ACTION_SET_*} écrivent directement dans les
     * {@code Property} correspondantes, sans passer par un {@link Frame} : elles sont donc
     * écrasées au prochain tick si une routine est active (voir la doc de classe).
     */
    private final BroadcastReceiver debugControlReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case ACTION_START_ROUTINE_1:
                    startRoutine1(
                            intent.getFloatExtra(EXTRA_CRUISE_SPEED_KMH, DEFAULT_CRUISE_SPEED_KMH),
                            intent.getBooleanExtra(EXTRA_LOOP, true));
                    break;

                case ACTION_STOP_ROUTINE:
                    stopRoutine();
                    break;

                case ACTION_SET_RPM:
                    data.rpm.set(intent.getIntExtra(EXTRA_VALUE, data.rpm.get()));
                    break;

                case ACTION_SET_SPEED:
                    data.speed.set((double) intent.getFloatExtra(EXTRA_VALUE, data.speed.get().floatValue()));
                    break;

                case ACTION_SET_CONTACT_ON:
                    data.contactOn.set(intent.getBooleanExtra(EXTRA_VALUE, data.contactOn.get()));
                    break;

                case ACTION_SET_ODOMETER:
                    data.odometer.set(intent.getLongExtra(EXTRA_VALUE, data.odometer.get()));
                    break;

                default:
                    break;
            }
        }
    };

    // --- Routines -----------------------------------------------------------------------------

    /**
     * Stoppe la routine active, le cas échéant, sans arrêter le simulateur lui-même : une
     * nouvelle routine peut être démarrée juste après. Sans effet si aucune routine n'est active.
     */
    private void stopRoutine() {
        ScheduledFuture<?> routine = activeRoutine;
        if (routine != null) {
            routine.cancel(false);
            activeRoutine = null;
        }
    }

    /**
     * Trajet type : contact mis, ralenti, accélération jusqu'à {@code cruiseSpeedKmh} (avec la
     * chute de régime à chaque changement de rapport simulé via {@link #GEARBOX}), croisière,
     * décélération jusqu'à l'arrêt, puis ralenti. Reboucle indéfiniment si {@code loop}, sinon
     * s'arrête d'elle-même à la fin du cycle. Remplace toute routine déjà active.
     * <p>
     * L'odomètre continue depuis sa valeur courante ({@link #persistOdometer}) : la routine n'en
     * prend pas en paramètre, seul {@link #ACTION_SET_ODOMETER} permet de le repositionner.
     * </p>
     *
     * @param cruiseSpeedKmh Vitesse de croisière visée (km/h).
     * @param loop           {@code true} pour reboucler le trajet indéfiniment.
     */
    private void startRoutine1(double cruiseSpeedKmh, boolean loop) {
        stopRoutine();
        data.contactOn.set(true);

        Routine1State state = new Routine1State(data.odometer.get(), cruiseSpeedKmh, loop);
        activeRoutine = executor.scheduleAtFixedRate(
                () -> tickRoutine1(state), 0, TICK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Calcule et émet les trames simulées pour un tick de la routine 1. Encadrée d'un try/catch :
     * une exception ici ne doit ni interrompre les ticks suivants, ni tuer silencieusement le
     * thread de {@link #executor} (voir la même précaution dans {@code CanableReader}).
     */
    private void tickRoutine1(Routine1State state) {
        try {
            long tick = state.tickCount++;

            double speedKmh = routine1SpeedAt(tick % CYCLE_TICKS, state.cruiseSpeedKmh);
            int rpm = speedKmh < 0.5 ? IDLE_RPM : rpmForSpeed(speedKmh);

            state.totalKm += speedKmh * (TICK_INTERVAL_MS / 3600000.0);

            emitRpmSpeed(rpm, speedKmh);
            emitContactOdometer(true, Math.round(state.totalKm));

            if (!state.loop && tick + 1 >= CYCLE_TICKS) {
                stopRoutine();
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur dans la routine de simulation 1", e);
        }
    }

    /**
     * Profil de vitesse (km/h) de la routine 1 en fonction de la position dans le cycle :
     * ralenti, accélération (courbe adoucie), croisière (légère ondulation pour éviter une
     * vitesse parfaitement plate), décélération (courbe adoucie), ralenti.
     */
    private static double routine1SpeedAt(long cycleTick, double cruiseSpeedKmh) {
        long t = cycleTick;

        if (t < IDLE_BEFORE_TICKS) {
            return 0;
        }
        t -= IDLE_BEFORE_TICKS;

        if (t < ACCEL_TICKS) {
            return cruiseSpeedKmh * smoothstep((double) t / ACCEL_TICKS);
        }
        t -= ACCEL_TICKS;

        if (t < CRUISE_TICKS) {
            double wiggleKmh = Math.sin(t * 0.2) * 2.0;
            return cruiseSpeedKmh + wiggleKmh;
        }
        t -= CRUISE_TICKS;

        if (t < DECEL_TICKS) {
            return cruiseSpeedKmh * (1 - smoothstep((double) t / DECEL_TICKS));
        }

        return 0; // Phase de ralenti finale (IDLE_AFTER_TICKS).
    }

    /**
     * Régime moteur (tr/min) correspondant à {@code speedKmh} d'après {@link #GEARBOX} :
     * interpolation linéaire du régime dans le rapport engagé pour cette vitesse.
     */
    private static int rpmForSpeed(double speedKmh) {
        Gear gear = GEARBOX[GEARBOX.length - 1];
        for (Gear candidate : GEARBOX) {
            if (speedKmh < candidate.speedToKmh) {
                gear = candidate;
                break;
            }
        }

        double clampedSpeed = Math.max(gear.speedFromKmh, Math.min(speedKmh, gear.speedToKmh));
        double span = gear.speedToKmh - gear.speedFromKmh;
        double progress = span <= 0 ? 0 : (clampedSpeed - gear.speedFromKmh) / span;

        return (int) Math.round(gear.rpmFromRpm + (gear.rpmToRpm - gear.rpmFromRpm) * progress);
    }

    /** Interpolation adoucie (accélération/décélération progressive plutôt que linéaire). */
    private static double smoothstep(double t) {
        double clamped = Math.max(0, Math.min(1, t));
        return clamped * clamped * (3 - 2 * clamped);
    }

    // --- Construction et émission des trames simulées --------------------------------------------

    /**
     * Encode {@code rpm}/{@code speedKmh} au format de la trame {@value #FRAME_ID_RPM_SPEED}
     * (inverse du décodage de {@link Frame0B6}) et la fait décoder.
     */
    private void emitRpmSpeed(int rpm, double speedKmh) {
        int rawRpm = clampUnsigned16(rpm * 8);
        int rawSpeed = clampUnsigned16((int) Math.round(speedKmh * 100));

        String dataHex = toHexByte(rawRpm >> 8) + toHexByte(rawRpm)
                + toHexByte(rawSpeed >> 8) + toHexByte(rawSpeed);
        dispatch(FRAME_ID_RPM_SPEED, dataHex);
    }

    /**
     * Encode {@code contactOn}/{@code odometerKm} au format de la trame
     * {@value #FRAME_ID_CONTACT_ODOMETER} (inverse du décodage de {@link Frame0F6}) et la fait
     * décoder. Les octets 1 et 2, ignorés par {@link Frame0F6#parse}, sont mis à zéro.
     */
    private void emitContactOdometer(boolean contactOn, long odometerKm) {
        int byte0 = contactOn ? 0x01 : 0x00;
        long clampedOdometer = Math.max(0, Math.min(odometerKm, 0xFFFFFFL));

        String dataHex = toHexByte(byte0) + "0000"
                + toHexByte((int) (clampedOdometer >> 16))
                + toHexByte((int) (clampedOdometer >> 8))
                + toHexByte((int) clampedOdometer);
        dispatch(FRAME_ID_CONTACT_ODOMETER, dataHex);
    }

    /**
     * Fait décoder {@code dataHex} par le {@link Frame} déclaré pour {@code id}, s'il en existe
     * un pour le véhicule ciblé. Encadré d'un garde-fou comme le lecteur réel : une exception de
     * décodage ne doit jamais interrompre la simulation.
     */
    private void dispatch(String id, String dataHex) {
        Frame frame = frames.get(id);
        if (frame == null) return;

        try {
            frame.parse(dataHex, data);
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur de décodage de la trame simulée " + id, e);
        }
    }

    private static int clampUnsigned16(int value) {
        return Math.max(0, Math.min(value, 0xFFFF));
    }

    private static String toHexByte(int value) {
        return String.format(Locale.ROOT, "%02X", value & 0xFF);
    }

    /** Un rapport de boîte de vitesses simulé, voir {@link #GEARBOX}. */
    private static final class Gear {
        final double speedFromKmh;
        final double speedToKmh;
        final int rpmFromRpm;
        final int rpmToRpm;

        Gear(double speedFromKmh, double speedToKmh, int rpmFromRpm, int rpmToRpm) {
            this.speedFromKmh = speedFromKmh;
            this.speedToKmh = speedToKmh;
            this.rpmFromRpm = rpmFromRpm;
            this.rpmToRpm = rpmToRpm;
        }
    }

    /** État (non partagé entre routines) d'une exécution de {@link #startRoutine1}. */
    private static final class Routine1State {
        long tickCount;
        double totalKm;
        final double cruiseSpeedKmh;
        final boolean loop;

        Routine1State(long odometerStartKm, double cruiseSpeedKmh, boolean loop) {
            this.totalKm = odometerStartKm;
            this.cruiseSpeedKmh = cruiseSpeedKmh;
            this.loop = loop;
        }
    }
}
