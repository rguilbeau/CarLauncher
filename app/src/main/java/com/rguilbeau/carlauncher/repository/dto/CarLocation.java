package com.rguilbeau.carlauncher.repository.dto;

import java.util.Date;

public class CarLocation {
    public double longitude;
    public double latitude;

    public CarLocation(double longitude, double latitude) {
        this.latitude = latitude;
        this.longitude = longitude;
    }
}
