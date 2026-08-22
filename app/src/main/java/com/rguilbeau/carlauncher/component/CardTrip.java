package com.rguilbeau.carlauncher.component;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.service.TripService;

import java.util.Locale;

/**
 * Composant d'interface utilisateur autonome héritant de {@link FrameLayout}.
 * <p>
 * Ce composant assure l'affichage des données de statistiques de trajet (distance parcourue et temps de conduite)
 * pour une application de type Car Launcher.
 * Il observe de manière dynamique les modifications apportées aux {@link SharedPreferences} afin de mettre à jour
 * l'affichage en temps réel.
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
     * Instance des préférences partagées utilisée pour lire les données du trajet.
     */
    private final SharedPreferences prefs;

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un fichier de layout XML.
     *
     * @param context Le contexte Android associé à l'environnement d'exécution.
     * @param attrs   Ensemble d'attributs XML passés au composant lors de son gonflage.
     */
    public CardTrip(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        // Inflation du layout interne associé au composant
        LayoutInflater.from(context).inflate(R.layout.card_trip, this, true);

        // Liaison des vues internes par leurs identifiants
        txtTripDistance = findViewById(R.id.txtTripDistance);
        txtTripTime = findViewById(R.id.txtTripTime);

        // Initialisation du gestionnaire de préférences
        prefs = context.getSharedPreferences(TripService.PREFS_NAME, Context.MODE_PRIVATE);

        // Écouteur de clic pour déclencher la réinitialisation des métriques
        // Attachement des écouteurs d'événements uniquement si la stratégie a bien été définie
        // On attache le clic sur button_root pour déclencher l'effet visuel (foreground)
        View root = findViewById(R.id.card_root);
        if (root != null) {
            root.setOnClickListener(v -> reset());
        } else {
            Log.e(TAG, "ID card_root not found (CardTrip)");
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

            String timeFormatted;
            if (hours > 0) {
                timeFormatted = String.format(Locale.FRANCE, "%dh%02d", hours, minutes);
            } else {
                timeFormatted = String.format(Locale.FRANCE, "%d min", minutes);
            }

            // --- NOUVELLE LOGIQUE D'AFFICHAGE DE LA DISTANCE ---
            String distanceFormatted;
            if (distanceKm >= 0.1f && distanceKm < 0.9f) {
                // En dessous de 1 km : on affiche 1 chiffre après la virgule (ex: 0,8)
                distanceFormatted = String.format(Locale.FRANCE, "%.1f", distanceKm);
            } else {
                // À partir de 1 km : on n'affiche plus de virgule (ex: 1, 2, 15)
                distanceFormatted = String.format(Locale.FRANCE, "%.0f", distanceKm);
            }

            post(() -> {
                if (txtTripDistance != null && txtTripTime != null) {
                    txtTripDistance.setText(distanceFormatted); // Utilisation de la variable formatée
                    txtTripTime.setText(timeFormatted);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error updating trip metrics from SharedPreferences", e);
        }
    }

    /**
     * Affiche une boîte de dialogue de confirmation pour réinitialiser les métriques du trajet
     * (distance et temps de conduite) dans les {@link SharedPreferences}.
     */
    public void reset() {
        try {
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle("Réinitialiser le trajet ?")
                    .setMessage("Voulez-vous vraiment remettre la distance et le temps à zéro ?")
                    .setPositiveButton("Oui", (dialog, which) -> {
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
        } catch (Exception e) {
            Log.e(TAG, "Error displaying reset confirmation dialog", e);
        }
    }

    /**
     * Méthode de cycle de vie appelée lorsque la vue est détachée de sa fenêtre parent.
     * Désenregistre l'écouteur de préférences afin d'éviter les fuites de mémoire (Memory Leaks).
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(this);
        }
    }
}