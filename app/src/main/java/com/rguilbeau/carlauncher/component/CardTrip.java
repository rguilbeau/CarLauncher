package com.rguilbeau.carlauncher.component;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.IBinder;
import android.util.AttributeSet;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.service.trip.TripListener;
import com.rguilbeau.carlauncher.service.trip.TripService;
import com.rguilbeau.carlauncher.service.trip.TripStats;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.Locale;

/**
 * Composant d'interface utilisateur autonome héritant de {@link FrameLayout}.
 * <p>
 * Ce composant assure l'affichage des données de statistiques de trajet (distance parcourue et temps de conduite).
 * Il s'abonne à {@link TripService} via {@link TripListener} afin de mettre à jour l'affichage en temps réel
 * et permet une réinitialisation manuelle (côté affichage uniquement) via un clic sur la carte.
 * </p>
 *
 * @author rguilbeau
 * @version 1.0
 */
public class CardTrip extends FrameLayout implements TripListener {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs et de débogage (Logcat).
     */
    private static final String TAG = "CardTrip";

    /**
     * Composant visuel affichant la distance de trajet convertie en kilomètres.
     */
    private final TextView txtTripDistance;

    /**
     * Composant visuel affichant le temps de trajet formaté (heures et minutes).
     */
    private final TextView txtTripTime;

    /**
     * Référence vers le service de trajet une fois la connexion établie.
     */
    private TripService tripService;

    /**
     * Indicateur d'état précisant si le composant est actuellement attaché au service de trajet.
     */
    private boolean isBound = false;

    /**
     * Gère le cycle de vie de la connexion avec le service de trajet.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            TripService.LocalBinder binder = (TripService.LocalBinder) service;
            tripService = binder.getService();
            tripService.addListener(CardTrip.this);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            tripService = null;
        }
    };

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un fichier de layout XML.
     * Inflate la vue, relie les composants graphiques et attache l'écouteur de clic pour le reset.
     *
     * @param context Le contexte Android associé à l'environnement d'exécution.
     * @param attrs   Ensemble d'attributs XML passés au composant lors de son gonflage.
     */
    public CardTrip(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(context).inflate(R.layout.card_trip, this, true);

        txtTripDistance = findViewById(R.id.txtTripDistance);
        txtTripTime = findViewById(R.id.txtTripTime);

        // Attachement de l'écouteur de clic sur la vue racine pour proposer la réinitialisation
        View root = findViewById(R.id.card_root);
        if (root != null) {
            root.setOnClickListener(v -> reset());
        } else {
            CarLog.e(TAG, "ID card_root not found (CardTrip)");
        }
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est rattachée à une fenêtre active.
     * Établit la connexion avec le service de trajet.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        Intent intent = new Intent(getContext(), TripService.class);
        isBound = getContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    /**
     * Callback déclenché à chaque mise à jour des statistiques de trajet par {@link TripService}.
     *
     * @param daily Statistiques affichées (remises à zéro par l'utilisateur).
     * @param full  Statistiques complètes de la journée (non utilisées ici).
     */
    @Override
    public void onTripUpdated(TripStats daily, TripStats full) {
        try {
            float distanceKm = daily.distanceMeters / 1000f;
            long totalDriveTime = daily.driveTimeMillis;

            long minutes = (totalDriveTime / (1000 * 60)) % 60;
            long hours = (totalDriveTime / (1000 * 60 * 60));

            // Formatage du temps de conduite en heure/minute
            String timeFormatted;
            if (hours > 0) {
                timeFormatted = String.format(Locale.FRANCE, "%dh%02d", hours, minutes);
            } else {
                timeFormatted = String.format(Locale.FRANCE, "%d min", minutes);
            }

            // Formatage de la distance selon la valeur (1 décimale sous 1 km, sans décimale au-delà)
            String distanceFormatted;
            if (distanceKm >= 0.1f && distanceKm < 0.9f) {
                distanceFormatted = String.format(Locale.FRANCE, "%.1f", distanceKm);
            } else {
                distanceFormatted = String.format(Locale.FRANCE, "%.0f", distanceKm);
            }

            post(() -> {
                if (txtTripDistance != null && txtTripTime != null) {
                    txtTripDistance.setText(distanceFormatted);
                    txtTripTime.setText(timeFormatted);
                }
            });
        } catch (Exception e) {
            CarLog.e(TAG, "Error updating trip metrics", e);
        }
    }

    /**
     * Affiche une boîte de dialogue de confirmation pour réinitialiser l'affichage des métriques
     * de trajet (distance et temps). Les statistiques réelles de la journée ({@code full}), utilisées
     * pour la persistance en base, ne sont pas affectées par ce reset.
     */
    public void reset() {
        try {
            AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle("Réinitialiser le trajet ?")
                    .setMessage("Voulez-vous vraiment remettre la distance et le temps à zéro ?")
                    .setPositiveButton("Oui", (dialogInterface, which) -> {
                        if (tripService != null) {
                            tripService.resetDaily();
                            Toast.makeText(getContext(), "Compteur réinitialisé !", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), "Service non connecté", Toast.LENGTH_SHORT).show();
                        }
                    })
                    .setNegativeButton("Non", null)
                    .show();

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
        } catch (Exception e) {
            CarLog.e(TAG, "Error displaying reset confirmation dialog", e);
        }
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est détachée de sa fenêtre parent.
     * Désabonne le composant et libère la connexion au service afin d'éviter les fuites de mémoire.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (isBound) {
            if (tripService != null) {
                tripService.removeListener(this);
            }
            getContext().unbindService(serviceConnection);
            isBound = false;
        }
    }
}
