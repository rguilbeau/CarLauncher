package com.rguilbeau.carlauncher.service.telemetry.canbus.data;

/**
 * Snapshot observable de l'état du véhicule, alimenté en temps réel par les décodeurs
 * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame} au fil des trames CAN
 * reçues. Chaque champ peut être observé indépendamment via {@link Property#observe}.
 */
public class VehicleData {

    /** Régime moteur, en tours par minute (tr/min). */
    public final Property<Integer> rpm = new Property<>(0);

    /** Vitesse du véhicule, en kilomètres par heure (km/h). */
    public final Property<Double> speed = new Property<>(0.0);

    /** État du contact (allumage) du véhicule : {@code true} si mis. */
    public final Property<Boolean> contactOn = new Property<>(false);

    /** Kilométrage total du véhicule, en kilomètres. */
    public final Property<Long> odometer = new Property<>(0L);
}
