package com.qf.vehicle;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

import java.util.Locale;

/**
 * Écran minimal du stub, uniquement destiné à faciliter le Run/Debug depuis Android Studio (sans
 * activité, l'IDE ne sait pas quoi lancer après l'installation) et à visualiser en direct l'état
 * poussé via adb.
 * <p>
 * Ne pilote rien elle-même : toutes les valeurs sont contrôlées via
 * {@code adb shell am broadcast -a com.qf.vehicle.debug.SET_CARBODY_STATE ...}
 * (voir {@link VehicleServiceStub}). Cet écran ne fait qu'afficher l'état courant.
 */
public class MainActivity extends Activity {

    private TextView statusView;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render(
                    intent.getIntExtra(VehicleServiceStub.EXTRA_SPEED, 0),
                    intent.getIntExtra(VehicleServiceStub.EXTRA_RPM, 0),
                    intent.getFloatExtra(VehicleServiceStub.EXTRA_MILEAGE, 0f),
                    intent.getBooleanExtra(VehicleServiceStub.EXTRA_CONNECTED, false),
                    intent.getBooleanExtra(VehicleServiceStub.EXTRA_INITIALIZED, false)
            );
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusView = new TextView(this);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(16f);
        statusView.setPadding(48, 48, 48, 48);
        setContentView(statusView);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);

        render(0, 0, 0f, false, false);
    }

    @Override
    protected void onStart() {
        super.onStart();

        IntentFilter filter = new IntentFilter(VehicleServiceStub.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }

        // Rattrape l'état courant si le service tournait déjà avant l'ouverture de cet écran.
        VehicleServiceStub service = VehicleServiceStub.getInstance();
        if (service != null) {
            render(service.getLastSpeed(), service.getLastRpm(), service.getLastMileageKm(),
                    service.isClientConnected(), service.isInitialized());
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(stateReceiver);
    }

    private void render(int speed, int rpm, float mileageKm, boolean connected, boolean initialized) {
        statusView.setText(String.format(Locale.FRANCE,
                "FAKE QF Vehicle (stub)\n\n"
                        + "Client CarLauncher connecté : %s\n"
                        + "SDK initialisé : %s\n\n"
                        + "Vitesse : %d km/h\n"
                        + "RPM : %d\n"
                        + "Kilométrage : %.1f km\n\n"
                        + "adb shell am broadcast -a %s \\\n  --ei speed <v> --ei rpm <v> --ef mileage <v>",
                connected ? "oui" : "non",
                initialized ? "oui" : "non",
                speed, rpm, mileageKm,
                VehicleServiceStub.ACTION_SET_CARBODY_STATE));
    }
}
