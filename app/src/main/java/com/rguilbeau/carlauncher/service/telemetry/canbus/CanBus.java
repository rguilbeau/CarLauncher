package com.rguilbeau.carlauncher.service.telemetry.canbus;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.FrameResolver;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Vehicle;
import com.rguilbeau.carlauncher.service.telemetry.canbus.reader.CanReader;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.Map;

/**
 * Point d'entrée du sous-système CAN : résout au démarrage les décodeurs {@link Frame}
 * disponibles pour le véhicule ciblé (via {@link FrameResolver}), puis démarre la lecture du bus
 * via le {@link CanReader} fourni. Les résultats du décodage sont exposés au fil de l'eau dans
 * {@link #getData()}.
 */
public class CanBus {

    /** Tag utilisé pour l'identification des messages de journalisation de cette classe. */
    private static final String TAG = "CanBus";

    /** Décodeurs disponibles pour le véhicule ciblé, indexés par id de trame CAN. */
    private final Map<String, Frame> frames;

    /** Snapshot observable de l'état du véhicule, mis à jour par les décodeurs de {@link #frames}. */
    private final VehicleData data;

    /** Source de trames CAN brutes fournie au constructeur. */
    private final CanReader reader;

    /** Évite un double appel à {@link CanReader#start}/{@link CanReader#stop} en cas d'appel en double. */
    private boolean started;

    /**
     * @param reader        Source de trames CAN à utiliser (ex:
     *                      {@link com.rguilbeau.carlauncher.service.telemetry.canbus.reader.canable.CanableReader}).
     * @param targetVehicle Véhicule pour lequel résoudre les décodeurs de trames.
     */
    public CanBus(CanReader reader, Vehicle targetVehicle) {
        this.frames = FrameResolver.resolve(targetVehicle);
        this.reader = reader;
        this.data = new VehicleData();
    }

    /**
     * @return Les données du véhicule, à observer via leurs {@code Property}.
     */
    public synchronized VehicleData getData() {
        return this.data;
    }

    /**
     * Démarre la lecture du bus CAN. Sans effet si déjà démarré.
     */
    public synchronized void start() {
        if (started) {
            CarLog.e(TAG, "start() appelé alors que le CanBus est déjà démarré, ignoré");
            return;
        }
        started = true;
        reader.start(this.data, this.frames);
    }

    /**
     * Arrête la lecture du bus CAN. Sans effet si non démarré.
     */
    public synchronized void stop() {
        if (!started) return;
        started = false;
        reader.stop();
    }
}
