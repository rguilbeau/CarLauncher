package com.rguilbeau.carlauncher.service.telemetry;

/**
 * Interface de rappel (callback) permettant aux composants de l'application
 * d'écouter et de réagir aux événements de télémétrie du véhicule
 * diffusés par le {@link CarTelemetryService}.
 * <p>
 * Toutes les méthodes ont une implémentation par défaut vide : chaque abonné n'implémente que
 * les valeurs dont il a réellement besoin.
 */
public interface CarTelemetryListener {

    /**
     * Appelée lorsque la vitesse instantanée du véhicule est mise à jour depuis le bus CAN.
     *
     * @param speedKmh La vitesse actuelle du véhicule en kilomètres par heure (km/h).
     */
    default void onSpeedUpdated(int speedKmh) {
    }

    /**
     * Appelée lorsque le régime moteur du véhicule est mis à jour depuis le bus CAN.
     *
     * @param rpm Le régime moteur actuel en tours par minute (tr/min).
     */
    default void onRpmUpdated(int rpm) {
    }

    /**
     * Appelée lorsque le kilométrage total du véhicule (odomètre) est mis à jour
     * depuis le bus CAN.
     * <p>
     * Transmis en {@code double} plutôt que {@code float} : au-delà de quelques dizaines de
     * milliers de km, un {@code float} (32 bits, ~7 chiffres significatifs) n'a plus assez de
     * précision pour distinguer des écarts de 0,1 km, ce qui fausse tout calcul de distance basé
     * dessus.
     *
     * @param totalKm Le kilométrage total actuel du véhicule, en kilomètres.
     */
    default void onMileageUpdated(double totalKm) {
    }
}
