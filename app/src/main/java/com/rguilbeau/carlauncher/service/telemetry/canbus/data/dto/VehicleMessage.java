package com.rguilbeau.carlauncher.service.telemetry.canbus.data.dto;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Message du véhicule à destination de l'utilisateur (alerte, avertissement ou simple
 * information), décodé depuis le can bus et exposé via
 * {@link com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData#vehicleMessage}.
 * <p>
 * Objet immuable, comparé par valeur (niveau et texte) : voir {@link #equals}.
 * </p>
 */
public class VehicleMessage {

    /**
     * Niveau de gravité d'un message, qui détermine son habillage dans la popup
     * ({@link com.rguilbeau.carlauncher.service.popup.PopupService}).
     */
    public enum Level {
        /**
         * Simple information, sans action requise (ex. : « Diagnostic en cours... »).
         */
        INFORMATION,

        /**
         * Avertissement nécessitant l'attention du conducteur (ex. : « Niveau de carburant trop
         * bas »).
         */
        WARNING,

        /**
         * Défaillance ou situation dangereuse nécessitant une action rapide (ex. : « Système de
         * freinage défaillant »).
         */
        DANGER
    }

    /**
     * Niveau de gravité du message.
     */
    private final Level level;

    /**
     * Texte du message, en français, affiché tel quel à l'utilisateur.
     */
    private final String message;

    /**
     * True si le message doit être affiché, false sinon
     */
    private final boolean visible;

    /**
     * Crée un message du véhicule.
     *
     * @param level   Le niveau de gravité du message.
     * @param visible True si le message doit être affiché, false sinon
     * @param message Le texte du message, affiché tel quel à l'utilisateur.
     */
    public VehicleMessage(Level level, boolean visible, String message) {
        this.level = level;
        this.visible = visible;
        this.message = message;
    }

    /**
     * Crée un message vide et non visible
     */
    public VehicleMessage() {
        this.level = Level.INFORMATION;
        this.visible = false;
        this.message = "";
    }

    /**
     * @return Le niveau de gravité du message.
     */
    public Level getLevel() {
        return this.level;
    }

    /**
     * @return Le texte du message, affiché tel quel à l'utilisateur.
     */
    public String getMessage() {
        return this.message;
    }

    /**
     * @return true si le message doit être affiché, false sinon
     */
    public boolean isVisible() {
        return this.visible;
    }

    /**
     * Égalité par valeur : indispensable car {@code Property} ne notifie que les changements
     * (via {@link Objects#equals}) et la trame 1A1 recrée une instance à chaque réception. Sans
     * cela, la popup serait réaffichée à chaque trame, même après fermeture par l'utilisateur.
     *
     * @param o L'objet à comparer.
     * @return {@code true} si {@code o} est un {@link VehicleMessage} de même niveau et de même
     * texte.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VehicleMessage)) {
            return false;
        }
        VehicleMessage that = (VehicleMessage) o;
        return level == that.level && visible == that.visible && Objects.equals(message, that.message);
    }

    /**
     * Hash cohérent avec {@link #equals} (niveau et texte).
     *
     * @return Le hash du message.
     */
    @Override
    public int hashCode() {
        return Objects.hash(level, message, visible);
    }

    /**
     * Représentation lisible destinée à la journalisation, de la forme
     * {@code "WARNING: Risque de verglas"}.
     *
     * @return Le niveau suivi du texte du message.
     */
    @NonNull
    @Override
    public String toString() {
        return level + ": " + message + "(" + (visible ? "affiché" : "masqué") + ")";
    }
}
