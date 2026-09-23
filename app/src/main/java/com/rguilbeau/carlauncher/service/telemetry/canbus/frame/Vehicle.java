package com.rguilbeau.carlauncher.service.telemetry.canbus.frame;

/**
 * Véhicules pris en charge par le décodage du bus CAN. Chaque valeur correspond à un jeu de
 * trames CAN spécifique (identifiants, encodage des données), rattaché aux classes {@link Frame}
 * via l'annotation {@link FrameInfo}.
 */
public enum Vehicle {
    PEUGEOT_407
}
