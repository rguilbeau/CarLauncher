package com.rguilbeau.carlauncher.service.trip;

/**
 * Instantané immuable des statistiques de trajet (distance et temps de conduite),
 * transmis aux {@link TripListener} par le {@link TripService}.
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

    public TripStats(float distanceMeters, long driveTimeMillis, String dayKey) {
        this.distanceMeters = distanceMeters;
        this.driveTimeMillis = driveTimeMillis;
        this.dayKey = dayKey;
    }
}
