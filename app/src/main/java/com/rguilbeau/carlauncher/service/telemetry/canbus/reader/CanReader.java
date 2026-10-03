package com.rguilbeau.carlauncher.service.telemetry.canbus.reader;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame;

import java.util.Map;

/**
 * Source de trames CAN brutes pour {@link com.rguilbeau.carlauncher.service.telemetry.canbus.CanBus}
 * (ex: adaptateur matériel, simulateur...). Une implémentation lit le média physique et route
 * chaque trame reçue vers le {@link Frame} correspondant (via {@code frames}), qui met alors à
 * jour {@code data}.
 */
public interface CanReader {

    /**
     * Démarre la lecture du bus CAN. Ne doit pas bloquer le thread appelant : le travail de
     * connexion/lecture doit être délégué à un thread dédié.
     *
     * @param data   Données du véhicule à mettre à jour au fil des trames décodées.
     * @param frames Décodeurs disponibles, indexés par id de trame CAN.
     */
    void start(VehicleData data, Map<String, Frame> frames);

    /**
     * Arrête la lecture et libère les ressources ouvertes par {@link #start}. Doit pouvoir être
     * appelée même si {@link #start} n'a jamais été appelée, ou plusieurs fois de suite, sans
     * lever d'exception.
     */
    void stop();
}
