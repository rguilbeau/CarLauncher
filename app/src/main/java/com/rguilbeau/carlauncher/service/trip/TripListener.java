package com.rguilbeau.carlauncher.service.trip;

/**
 * Interface de communication permettant aux composants liés d'interagir avec {@link TripService}
 * (patron de conception Observateur, à l'image de
 * {@link com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener}).
 */
public interface TripListener {

    /**
     * Invoquée à chaque mise à jour des statistiques de trajet.
     *
     * @param daily Statistiques affichées à l'utilisateur, remises à zéro par
     *              {@link TripService#resetDaily()}.
     * @param full  Statistiques réelles et complètes de la journée, jamais affectées
     *              par un reset manuel — destinées à la persistance en base.
     */
    void onTripUpdated(TripStats daily, TripStats full);
}
