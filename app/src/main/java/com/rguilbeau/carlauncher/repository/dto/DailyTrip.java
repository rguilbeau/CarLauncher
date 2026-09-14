package com.rguilbeau.carlauncher.repository.dto;

import java.util.Date;

public class DailyTrip {

    /**
     * Jour auquel se rattache ce trajet.
     */
    public Date date;

    /**
     * Distance totale parcourue ce jour-là, en kilomètres.
     */
    public double distanceKm;

    /**
     * Temps de conduite total pour ce jour-là, en minutes.
     */
    public int timeMinutes;

    /**
     * Construit un nouvel instantané de trajet journalier.
     *
     * @param date         Le jour auquel se rattache ce trajet.
     * @param distanceKm   La distance totale parcourue, en kilomètres.
     * @param timeMinutes  Le temps de conduite total, en minutes.
     */
    public DailyTrip(Date date, double distanceKm, int timeMinutes) {
        this.date = date;
        this.distanceKm = distanceKm;
        this.timeMinutes = timeMinutes;
    }
}
