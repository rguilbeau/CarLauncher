package com.rguilbeau.carlauncher.service.telemetry;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class CarTelemetryService extends Service {

    private static final String TAG = "CarTelemetryService";
    private final IBinder binder = new LocalBinder();
    private final List<CarTelemetryListener> listeners = new ArrayList<>();
    private boolean isAccOn = true;
    private int currentSpeed = 0;
    private int currentRpm = 0;

    public class LocalBinder extends Binder {
        public CarTelemetryService getService() {
            return CarTelemetryService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // --- Gestion des Abonnés (Observer Pattern) ---

    public synchronized void addListener(CarTelemetryListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            // On informe immédiatement le nouvel abonné de l'état actuel
            listener.onAccStateChanged(isAccOn);
            listener.onTelemetryUpdated(currentSpeed, currentRpm);
        }
    }

    public synchronized void removeListener(CarTelemetryListener listener) {
        listeners.remove(listener);
    }

    // --- Réception des événements système ---

    private final BroadcastReceiver carReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case "com.qf.action.ACC_ON":
                    isAccOn = true;
                    notifyAccChanged(true);
                    break;

                case "com.qf.action.ACC_OFF":
                    currentSpeed = 0;
                    currentRpm = 0;
                    notifyTelemetry(currentSpeed, currentRpm);

                    isAccOn = false;
                    notifyAccChanged(false);
                    break;

                case "com.qf.vehicle.action.DATA_SHARE":
                    // 1. Récupération du tableau d'octets de la voiture
                    byte[] data = intent.getByteArrayExtra("extra_DATA_SHARE");

                    if (data != null && data.length > 10) {
                        // VRAIE VOITURE : Décodage des octets
                        currentSpeed = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
                        currentRpm = ((data[9] & 0xFF) << 8) | (data[10] & 0xFF);
                    } else {
                        // MODE DEBUG ADB : Lecture directe si data est absent
                        currentSpeed = intent.getIntExtra("speed", currentSpeed);
                        currentRpm = intent.getIntExtra("rpm", currentRpm);
                    }

                    notifyTelemetry(currentSpeed, currentRpm);
                    break;
            }
        }
    };

    private synchronized void notifyAccChanged(boolean accOn) {
        for (CarTelemetryListener listener : listeners) {
            listener.onAccStateChanged(accOn);
        }
    }

    private synchronized void notifyTelemetry(int speed, int rpm) {
        for (CarTelemetryListener listener : listeners) {
            listener.onTelemetryUpdated(speed, rpm);
        }
    }

    public synchronized boolean isAccOn() {
        return isAccOn;
    }

    public synchronized int getCurrentSpeed() {
        return currentSpeed;
    }

    public synchronized int getCurrentRpm() {
        return currentRpm;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        registerTelemetrySubscription();

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.qf.action.ACC_ON");
        filter.addAction("com.qf.action.ACC_OFF");
        //filter.addAction("com.qf.action.READY_GO_SLEEP");
        filter.addAction("com.qf.vehicle.action.DATA_SHARE");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(carReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(carReceiver, filter);
        }
    }

    private void registerTelemetrySubscription() {
        try {
            String pkgName = getPackageName();
            String pkgs = Settings.Global.getString(getContentResolver(), "KeyAllPackages");
            if (pkgs == null || pkgs.isEmpty()) {
                pkgs = pkgName;
            } else if (!pkgs.contains(pkgName)) {
                pkgs += "," + pkgName;
            }
            Settings.Global.putString(getContentResolver(), "KeyAllPackages", pkgs);
            Settings.Global.putInt(getContentResolver(), pkgName + "KeyShareCarbodyState", 1);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(carReceiver);
    }
}