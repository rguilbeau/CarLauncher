package com.rguilbeau.carlauncher.service.telemetry.canbus.frame;

import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;

/**
 * Décodeur d'une trame CAN précise (identifiée par {@link FrameInfo}). Une implémentation
 * interprète les octets de données de la trame et met à jour les {@code Property} concernées de
 * {@link VehicleData}.
 */
public interface Frame {

    /**
     * Décode les données d'une trame CAN reçue et met à jour {@code vehicleData} en conséquence.
     * Appelée sur le thread de lecture du bus CAN (jamais le thread principal), à chaque
     * réception d'une trame dont l'id correspond à celui déclaré via {@link FrameInfo}.
     * <p>
     * Le lecteur CAN encadre cet appel d'un garde-fou (try/catch) : une exception ici (ex: trame
     * plus courte que prévu) est journalisée et n'interrompt pas la lecture des trames suivantes,
     * mais il reste préférable de vérifier la longueur de {@code dataHex} avant de l'indexer.
     * </p>
     *
     * @param dataHex     Octets de données de la trame, encodés en hexadécimal (sans le préfixe
     *                    de type ni l'id CAN), de longueur égale au DLC de la trame ×2 caractères.
     * @param vehicleData Données du véhicule à mettre à jour.
     */
    void parse(String dataHex, VehicleData vehicleData);
}
