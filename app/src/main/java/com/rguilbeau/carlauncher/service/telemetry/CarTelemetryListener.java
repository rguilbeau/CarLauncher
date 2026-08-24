package com.rguilbeau.carlauncher.service.telemetry;

/**
 * Interface de rappel (callback) permettant aux composants de l'application
 * d'écouter et de réagir aux événements de télémétrie du véhicule
 * diffusés par le {@link CarTelemetryService}.
 */
public interface CarTelemetryListener {

    /**
     * Appelée lorsque l'état de l'alimentation (contact) du véhicule change.
     *
     * @param accEnabled true si le contact est mis (ACC ON), false s'il est coupé (ACC OFF).
     */
    void onAccStateChanged(boolean accEnabled);

    /**
     * Appelée lorsque de nouvelles données de conduite sont reçues en temps réel
     * depuis le réseau (bus CAN) du véhicule.
     *
     * @param speed La vitesse actuelle du véhicule en kilomètres par heure (km/h).
     * @param rpm   Le régime moteur actuel en tours par minute (tr/min).
     */
    void onTelemetryUpdated(int speed, int rpm);
}