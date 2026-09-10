package com.rguilbeau.carlauncher.repository;

import com.rguilbeau.carlauncher.repository.dto.DailyTrip;

public class TripDailyRepository extends AbstractRepository {

    private static TripDailyRepository instance;

    private TripDailyRepository() {
    }

    public static synchronized TripDailyRepository get() {
        if (instance == null) {
            instance = new TripDailyRepository();
        }
        return instance;
    }

    public void update(DailyTrip trip) {
        String query = "INSERT INTO trip_history (day_trip, distance, duration) " +
                "VALUES (?, ?, ?) " +
                "ON CONFLICT (day_trip) DO UPDATE SET " +
                "distance = EXCLUDED.distance, " +
                "duration = EXCLUDED.duration;";

        java.sql.Date sqlDate = new java.sql.Date(trip.date.getTime());
        client.exec(query, sqlDate, trip.distancekm, trip.timeMinutes);
    }
}