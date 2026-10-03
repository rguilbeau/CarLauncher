package com.rguilbeau.carlauncher.repository;

import com.rguilbeau.carlauncher.repository.dto.DailyTrip;

public class TripDailyRepository extends AbstractRepository {

    /**
     * Instance unique du repository (patron de conception Singleton).
     */
    private static TripDailyRepository instance;

    /**
     * Constructeur privé imposant le passage par {@link #get()} (patron de conception Singleton).
     */
    private TripDailyRepository() {
    }

    /**
     * Récupère l'instance unique du repository.
     *
     * @return L'instance unique de {@link TripDailyRepository}.
     */
    public static synchronized TripDailyRepository get() {
        if (instance == null) {
            instance = new TripDailyRepository();
        }
        return instance;
    }

    /**
     * Empile un upsert des statistiques du jour dans la table {@code trip_history}.
     *
     * @param trip Les statistiques journalières à enregistrer.
     */
    public void update(DailyTrip trip) {
        String query = "INSERT INTO trip_history (day_trip, distance, duration) " +
                "VALUES (?, ?, ?) " +
                "ON CONFLICT (day_trip) DO UPDATE SET " +
                "distance = EXCLUDED.distance, " +
                "duration = EXCLUDED.duration;";

        java.sql.Date sqlDate = new java.sql.Date(trip.date.getTime());
        worker.addQueue(query, sqlDate, trip.distanceKm, trip.timeMinutes);
    }
}