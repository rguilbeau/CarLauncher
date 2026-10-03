package com.rguilbeau.carlauncher.service.telemetry.canbus.data;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.dto.VehicleMessage;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Snapshot observable de l'état du véhicule, alimenté en temps réel par les décodeurs
 * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame} au fil des trames CAN
 * reçues. Chaque champ peut être observé indépendamment via {@link Property#bind(Consumer)}.
 */
public class VehicleData {

    /** Régime moteur, en tours par minute (tr/min). */
    public final Property<Integer> rpm = new Property<>(0);

    /** Vitesse du véhicule, en kilomètres par heure (km/h). */
    public final Property<Integer> speed = new Property<>(0);

    /** État du contact (allumage) du véhicule : {@code true} si mis. */
    public final Property<Boolean> contactOn = new Property<>(false);

    /**
     * État du moteur : {@code true} s'il tourne, c'est-à-dire contact mis et régime supérieur à 0.
     * Mis à jour par les décodeurs des trames portant {@link #rpm} et {@link #contactOn}.
     */
    public final Property<Boolean> engineOn = new Property<>(false);

    /** Kilométrage total du véhicule, en kilomètres (résolution 0,1 km). */
    public final Property<Double> odometer = new Property<>();

    /** Température extérieure */
    public final Property<Integer> outsideTemperature = new Property<>();

    /** Message d'information. Si optional est vide, aucun message d'information d'affiché */
    public final Property<VehicleMessage> vehicleMessage = new Property<>();
}
