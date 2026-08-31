package com.rguilbeau.carlauncher.component;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.AttributeSet;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.service.TripService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.Locale;

/**
 * Composant d'interface utilisateur autonome héritant de {@link FrameLayout}.
 * <p>
 * Ce composant assure l'affichage des données de statistiques de trajet (distance parcourue et temps de conduite).
 * Il observe de manière dynamique les modifications apportées aux {@link SharedPreferences} afin de mettre à jour
 * l'affichage en temps réel et permet une réinitialisation manuelle via un clic sur la carte.
 * </p>
 *
 * @author rguilbeau
 * @version 1.0
 */
public class CardTrip extends FrameLayout implements SharedPreferences.OnSharedPreferenceChangeListener {

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
     * Instance des préférences partagées utilisée pour lire et modifier les données du trajet.
     */
    private final SharedPreferences prefs;

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

        prefs = context.getSharedPreferences(TripService.PREFS_NAME, Context.MODE_PRIVATE);

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
     * Enregistre l'écouteur de préférences et déclenche la mise à jour initiale de l'interface.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (prefs != null) {
            prefs.registerOnSharedPreferenceChangeListener(this);
        }

        update();
    }

    /**
     * Callback déclenché automatiquement lors de la modification d'une valeur dans les {@link SharedPreferences}.
     *
     * @param sharedPreferences L'instance des préférences partagées modifiée.
     * @param key               La clé correspondant à la donnée modifiée.
     */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @Nullable String key) {
        if (TripService.KEY_DISTANCE.equals(key) || TripService.KEY_DRIVE_TIME.equals(key)) {
            update();
        }
    }

    /**
     * Lit les métriques du trajet depuis les {@link SharedPreferences}, effectue les conversions d'unités,
     * formate le temps de conduite et met à jour les composants visuels sur le thread UI.
     */
    public void update() {
        try {
            float distanceKm = prefs.getFloat(TripService.KEY_DISTANCE, 0f) / 1000f;
            long totalDriveTime = prefs.getLong(TripService.KEY_DRIVE_TIME, 0L);

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
            CarLog.e(TAG, "Error updating trip metrics from SharedPreferences", e);
        }
    }

    /**
     * Affiche une boîte de dialogue de confirmation pour réinitialiser les métriques du trajet
     * (distance et temps de conduite) dans les {@link SharedPreferences}.
     */
    public void reset() {
        try {
            AlertDialog dialog = new AlertDialog.Builder(getContext())
                    .setTitle("Réinitialiser le trajet ?")
                    .setMessage("Voulez-vous vraiment remettre la distance et le temps à zéro ?")
                    .setPositiveButton("Oui", (dialogInterface, which) -> {
                        if (prefs != null) {
                            prefs.edit()
                                    .putFloat(TripService.KEY_DISTANCE, 0f)
                                    .putLong(TripService.KEY_DRIVE_TIME, 0L)
                                    .apply();

                            Toast.makeText(getContext(), "Compteur réinitialisé !", Toast.LENGTH_SHORT).show();
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
     * Désenregistre l'écouteur de préférences afin d'éviter les fuites de mémoire.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }
    }
}