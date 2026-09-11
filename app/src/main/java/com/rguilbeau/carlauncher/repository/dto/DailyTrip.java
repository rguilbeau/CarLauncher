package com.rguilbeau.carlauncher.repository.dto;

import java.util.Date;

public class DailyTrip {

    public Date date;
    public double distanceKm;
    public int timeMinutes;

    public DailyTrip(Date date, double distanceKm, int timeMinutes) {
        this.date = date;
        this.distanceKm = distanceKm;
        this.timeMinutes = timeMinutes;
    }
}
