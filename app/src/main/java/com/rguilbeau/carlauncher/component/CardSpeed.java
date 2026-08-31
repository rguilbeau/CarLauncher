package com.rguilbeau.carlauncher.component;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.AttributeSet;

import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

/**
 * Composant d'interface utilisateur autonome héritant de {@link FrameLayout}.
 * <p>
 * Ce composant affiche la vitesse instantanée du véhicule en km/h ainsi que le régime moteur (RPM).
 * Il s'abonne de manière autonome au {@link CarTelemetryService} pour lire les
 * trames CANbus en temps réel lorsque la vue est affichée.
 * </p>
 *
 * @author rguilbeau
 * @version 2.0 (CANbus)
 */
public class CardSpeed extends FrameLayout implements CarTelemetryListener {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs et de débogage (Logcat).
     */
    private static final String TAG = "CardSpeed";

    /**
     * Composant visuel affichant la vitesse instantanée du véhicule en km/h.
     */
    private final TextView txtSpeed;

    /**
     * Barre de progression affichant visuellement le régime moteur (RPM).
     */
    private final ProgressBar progressRpm;

    /**
     * Référence vers le service de télémétrie de la voiture.
     */
    private CarTelemetryService telemetryService;

    /**
     * Indicateur d'état précisant si la vue est actuellement connectée (bind) au service de télémétrie.
     */
    private boolean isBound = false;

    /**
     * Gestionnaire de connexion entre la vue et le service de télémétrie.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(CardSpeed.this);
            isBound = true;
            CarLog.d(TAG, "Connected to CarTelemetryService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            telemetryService = null;
            CarLog.d(TAG, "Disconnected from CarTelemetryService");
        }
    };

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un fichier de layout XML.
     * Inflate le layout interne et relie les composants graphiques.
     *
     * @param context Le contexte Android associé à l'environnement d'exécution.
     * @param attrs   Ensemble d'attributs XML passés au composant lors de son gonflage.
     */
    public CardSpeed(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(context).inflate(R.layout.card_speed, this, true);

        txtSpeed = findViewById(R.id.txtSpeed);
        progressRpm = findViewById(R.id.progressRpm);

        if (isBound) {
            txtSpeed.setText(String.valueOf(telemetryService.getCurrentSpeed()));
            progressRpm.setProgress(Math.min(telemetryService.getCurrentRpm(), 6500), true);
        }
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est rattachée à une fenêtre active.
     * Se connecte au service de télémétrie pour écouter les trames en temps réel.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            Intent intent = new Intent(getContext(), CarTelemetryService.class);
            getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            CarLog.e(TAG, "Error binding to CarTelemetryService", e);
        }
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est détachée de sa fenêtre parent.
     * Se désabonne du service pour éviter les fuites de mémoire.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (isInEditMode()) return;

        try {
            if (isBound) {
                if (telemetryService != null) {
                    telemetryService.removeListener(this);
                }
                getContext().unbindService(serviceConnection);
                isBound = false;
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error disconnecting telemetry service", e);
        }
    }

    /**
     * Déclenchée lors d'un changement d'état du contact du véhicule.
     *
     * @param isAccOn true si le contact est mis, false sinon.
     */
    @Override
    public void onAccStateChanged(boolean isAccOn) {
    }

    /**
     * Reçoit les nouvelles valeurs de télémétrie et met à jour l'affichage sur le thread principal.
     *
     * @param speed La vitesse instantanée du véhicule en km/h.
     * @param rpm   Le régime moteur en tr/min.
     */
    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        post(() -> {
            if (txtSpeed != null) {
                txtSpeed.setText(String.valueOf(speed));
            }

            if (progressRpm != null) {
                progressRpm.setProgress(Math.min(rpm, 6500), true);
            }
        });
    }
}