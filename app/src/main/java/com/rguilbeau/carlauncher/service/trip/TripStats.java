package com.rguilbeau.carlauncher.service.trip;

/**
 * Instantané immuable des statistiques de trajet (distance et temps de conduite), fourni par
 * {@link TripService} soit poussé aux {@link TripListener} abonnés, soit lu de manière synchrone
 * via {@link TripService#getFullStats()}.
 */
public class TripStats {

    /**
     * Distance parcourue, en mètres.
     */
    public final float distanceMeters;

    /**
     * Temps de conduite accumulé, en millisecondes.
     */
    public final long driveTimeMillis;

    /**
     * Jour auquel ces statistiques sont rattachées, au format "yyyy-MM-dd".
     */
    public final String dayKey;

    /**
     * Construit un nouvel instantané de statistiques de trajet.
     *
     * @param distanceMeters  La distance parcourue, en mètres.
     * @param driveTimeMillis Le temps de conduite accumulé, en millisecondes.
     * @param dayKey          Le jour auquel ces statistiques sont rattachées ("yyyy-MM-dd").
     */
    public TripStats(float distanceMeters, long driveTimeMillis, String dayKey) {
        this.distanceMeters = distanceMeters;
        this.driveTimeMillis = driveTimeMillis;
        this.dayKey = dayKey;
    }
}
