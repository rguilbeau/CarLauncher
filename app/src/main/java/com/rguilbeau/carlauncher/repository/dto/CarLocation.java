package com.rguilbeau.carlauncher.repository.dto;

import java.util.Date;

public class CarLocation {

    /**
     * Longitude de la position géographique du véhicule.
     */
    public double longitude;

    /**
     * Latitude de la position géographique du véhicule.
     */
    public double latitude;

    /**
     * Construit une nouvelle position géographique.
     *
     * @param longitude La longitude du véhicule.
     * @param latitude  La latitude du véhicule.
     */
    public CarLocation(double longitude, double latitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
