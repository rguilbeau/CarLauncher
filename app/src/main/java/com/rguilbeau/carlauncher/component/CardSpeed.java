package com.rguilbeau.carlauncher.component;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService;

/**
 * Composant d'interface utilisateur autonome héritant de {@link FrameLayout}.
 * <p>
 * Ce composant affiche la vitesse instantanée du véhicule en km/h.
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
     * Composant visuel affichant la vitesse en km/h.
     */
    private final TextView txtSpeed;
    /**
     * Composant visuel affichant les rpm
     */
    private final ProgressBar progressRpm;

    /**
     * Référence vers le service de télémétrie de la voiture.
     */
    private CarTelemetryService telemetryService;

    /**
     * État de la connexion au service.
     */
    private boolean isBound = false;

    /**
     * Gestionnaire de connexion entre la vue et le service.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            // Abonnement aux événements du véhicule
            telemetryService.addListener(CardSpeed.this);
            isBound = true;
            Log.d(TAG, "Connected to CarTelemetryService");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            telemetryService = null;
            Log.d(TAG, "Disconnected from CarTelemetryService");
        }
    };

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un fichier de layout XML.
     *
     * @param context Le contexte Android associé à l'environnement d'exécution.
     * @param attrs   Ensemble d'attributs XML passés au composant lors de son gonflage.
     */
    public CardSpeed(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        // Inflation du layout interne associé au composant
        LayoutInflater.from(context).inflate(R.layout.card_speed, this, true);

        // Liaison de la vue interne
        txtSpeed = findViewById(R.id.txtSpeed);
        progressRpm = findViewById(R.id.progressRpm);

        if(isBound) {
            txtSpeed.setText(String.valueOf(telemetryService.getCurrentSpeed()));
            progressRpm.setProgress(Math.min(telemetryService.getCurrentRpm(), 6500), true);
        }
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est rattachée à une fenêtre active.
     * Se connecte au service CANbus pour écouter la vitesse.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            Intent intent = new Intent(getContext(), CarTelemetryService.class);
            // On se lie au service sans le démarrer de force (le launcher principal devrait déjà l'avoir démarré)
            getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de la liaison au CarTelemetryService", e);
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
            Log.e(TAG, "Erreur lors de la déconnexion du service", e);
        }
    }

    // --- Implémentation de CarEventListener ---

    @Override
    public void onAccStateChanged(boolean isAccOn) {
    }

    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        // Utilisation de post() pour s'assurer que la modification UI a lieu sur le Thread Principal (Main Thread)
        post(() -> {
            if (txtSpeed != null) {
                // On affiche directement la vitesse exacte fournie par le CANbus de la voiture
                txtSpeed.setText(String.valueOf(speed));
            }

            if (progressRpm != null) {
                // Plafonnement à 6000 au cas où la valeur dépasse légèrement
                progressRpm.setProgress(Math.min(rpm, 6500), true);
            }
        });
    }
}