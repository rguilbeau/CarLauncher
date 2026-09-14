package com.rguilbeau.carlauncher.repository;

import com.rguilbeau.carlauncher.repository.dto.CarLocation;

public class CarLocationRepository extends AbstractRepository {

    /**
     * Instance unique du repository (patron de conception Singleton).
     */
    private static CarLocationRepository instance;

    /**
     * Récupère l'instance unique du repository.
     *
     * @return L'instance unique de {@link CarLocationRepository}.
     */
    public static synchronized CarLocationRepository get() {
        if (instance == null) {
            instance = new CarLocationRepository();
        }
        return instance;
    }

    /**
     * Constructeur privé imposant le passage par {@link #get()} (patron de conception Singleton).
     */
    private CarLocationRepository() {
    }

    /**
     * Empile une mise à jour de la position du véhicule (upsert sur la ligne unique de la table).
     *
     * @param location La nouvelle position géographique du véhicule.
     */
    public void setLocation(CarLocation location) {
        String query = "INSERT INTO car_location (id, latitude, longitude, updated_at) " +
                "VALUES (1, ?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "latitude = EXCLUDED.latitude, " +
                "longitude = EXCLUDED.longitude, " +
                "updated_at = CURRENT_TIMESTAMP;";

        worker.addQueue(query, location.latitude, location.longitude);
    }
}