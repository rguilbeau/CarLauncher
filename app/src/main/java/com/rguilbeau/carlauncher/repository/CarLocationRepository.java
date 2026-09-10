package com.rguilbeau.carlauncher.repository;

import com.rguilbeau.carlauncher.repository.dto.CarLocation;

public class CarLocationRepository extends AbstractRepository {

    private static CarLocationRepository instance;

    public static synchronized CarLocationRepository get() {
        if (instance == null) {
            instance = new CarLocationRepository();
        }
        return instance;
    }

    private CarLocationRepository() {
    }

    public void setLocation(CarLocation location) {
        String query = "INSERT INTO car_location (id, latitude, longitude, updated_at) " +
                "VALUES (1, ?, ?, CURRENT_TIMESTAMP) " +
                "ON CONFLICT (id) DO UPDATE SET " +
                "latitude = EXCLUDED.latitude, " +
                "longitude = EXCLUDED.longitude, " +
                "updated_at = CURRENT_TIMESTAMP;";

        client.exec(query, location.latitude, location.longitude);
    }
}