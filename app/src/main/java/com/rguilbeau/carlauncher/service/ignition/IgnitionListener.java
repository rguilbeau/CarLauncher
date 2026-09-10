package com.rguilbeau.carlauncher.service.ignition;

/**
 * Interface de rappel (callback) permettant aux composants de l'application
 * d'écouter et de réagir aux changements d'état du contact (ignition) du véhicule
 * diffusés par le {@link IgnitionService}.
 */
public interface IgnitionListener {

    /**
     * Appelée lorsque l'état de l'alimentation (contact) du véhicule change.
     *
     * @param accEnabled true si le contact est mis (ACC ON), false s'il est coupé (ACC OFF).
     */
    void onAccStateChanged(boolean accEnabled);
}
