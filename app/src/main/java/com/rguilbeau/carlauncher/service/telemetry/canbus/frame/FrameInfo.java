package com.rguilbeau.carlauncher.service.telemetry.canbus.frame;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation marquant une classe {@link Frame} comme décodeur d'une trame CAN précise.
 * Utilisée par {@link FrameResolver} pour découvrir et indexer automatiquement, au démarrage,
 * l'ensemble des décodeurs disponibles pour un véhicule donné.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FrameInfo {

    /**
     * Identifiant CAN de la trame décodée, en hexadécimal majuscule (ex: "0B6"), tel que reçu
     * depuis l'adaptateur (format SLCAN).
     */
    String id();

    /**
     * Véhicule auquel s'applique ce décodage (un même identifiant de trame peut avoir une
     * signification différente d'un véhicule à l'autre).
     */
    Vehicle vehicle();
}
