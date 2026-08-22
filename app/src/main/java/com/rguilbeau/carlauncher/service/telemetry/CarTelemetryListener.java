package com.rguilbeau.carlauncher.service.telemetry;

public interface CarTelemetryListener {

    public void onAccStateChanged(boolean accEnabled);

    public void onTelemetryUpdated(int speed, int rpm);

}
