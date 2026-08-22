package com.rguilbeau.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.rguilbeau.carlauncher.manager.AutoPlayManager;
import com.rguilbeau.carlauncher.manager.PermissionManager;
import com.rguilbeau.carlauncher.manager.UncaughtExceptionLoggerManager;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener;
import com.rguilbeau.carlauncher.service.TripService;

/**
 * Activité principale du Car Launcher.
 */
public class MainActivity extends AppCompatActivity implements CarTelemetryListener {

    private static final String TAG = "MainActivity";

    private AutoPlayManager autoPlayManager;

    // --- Connexion au CarTelemetryService ---
    private CarTelemetryService telemetryService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            // L'abonnement déclenche instantanément onAccStateChanged(true) au démarrage
            telemetryService.addListener(MainActivity.this);
            isBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            telemetryService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Configuration de l'affichage
        hideSystemUI();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Vérifie et affiche le dernier crash s'il y en a eu un
        UncaughtExceptionLoggerManager.showLastCrash(this);

        // 2. Initialisation de l'AutoPlayManager
        autoPlayManager = new AutoPlayManager(this);

        // Connexion au service CANbus pour écouter l'allumage du contact
        Intent intent = new Intent(this, CarTelemetryService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        // 3. Gestion de la permission GPS
        if (PermissionManager.hasLocationPermission(this)) {
            startTripService();
        } else {
            PermissionManager.requestLocationPermission(this);
        }
    }

    // --- Implémentation de CarEventListener ---

    @Override
    public void onAccStateChanged(boolean isAccOn) {
        if (isAccOn) {
            Log.i(TAG, "Contact mis (ACC_ON) - Lancement de l'Autoplay");
            if (autoPlayManager != null) {
                autoPlayManager.startAutoplayDelayed();
            }
        }
    }

    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        // MainActivity n'a pas besoin de la vitesse, on laisse vide
    }

    // ------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();

        if (!PermissionManager.hasNotificationPermission(this)) {
            Toast.makeText(this, "Veuillez autoriser l'accès aux notifications pour la musique", Toast.LENGTH_LONG).show();
            PermissionManager.openNotificationSettings(this);
        }
    }

    private void startTripService() {
        try {
            Intent intent = new Intent(this, TripService.class);
            startService(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start TripService", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        PermissionManager.handlePermissionResult(requestCode, grantResults, new PermissionManager.PermissionCallback() {
            @Override
            public void onGranted() {
                startTripService();
                recreate();
            }

            @Override
            public void onDenied() {
                Toast.makeText(MainActivity.this, "Permission GPS requise pour le fonctionnement optimal", Toast.LENGTH_LONG).show();
            }
        });
    }

    private void hideSystemUI() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        } catch (Exception e) {
            Log.e(TAG, "Error applying immersive mode", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Nettoyage pour éviter les fuites de mémoire
        if (isBound && telemetryService != null) {
            telemetryService.removeListener(this);
            unbindService(serviceConnection);
            isBound = false;
        }
        if (autoPlayManager != null) {
            autoPlayManager.stop();
        }
    }
}