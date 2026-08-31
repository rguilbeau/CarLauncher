package com.rguilbeau.carlauncher.component;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.util.AttributeSet;

import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.service.NotificationService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

/**
 * Composant d'interface utilisateur affichant la carte dédiée à Google Maps.
 * Affiche l'état par défaut ou les informations de guidage en direct (durée, distance, heure d'arrivée, prochaine direction et icône)
 * reçues via les diffusions de notifications du {@link NotificationService}.
 *
 * @author rguilbeau
 * @version 1.0
 */
@SuppressLint("SetTextI18n")
public class CardMaps extends FrameLayout implements View.OnClickListener {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "CardMaps";

    /**
     * Zone de texte affichant la description par défaut ou le résumé du trajet (temps restant, distance, ETA).
     */
    private final TextView txtDescription;

    /**
     * Conteneur des éléments de direction (icône et texte de la prochaine manœuvre).
     */
    private final LinearLayout layoutDirection;

    /**
     * Composant graphique affichant l'icône de la prochaine direction à prendre.
     */
    private final ImageView imageViewDirection;

    /**
     * Zone de texte affichant la distance avant la prochaine manœuvre ou le nom de la direction.
     */
    private final TextView txtDirection;

    /**
     * Récepteur d'intentions interceptant les mises à jour de navigation transmises par le NotificationService.
     */
    private final BroadcastReceiver mapsReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (NotificationService.ACTION_MAPS_UPDATE.equals(intent.getAction())) {
                boolean isNavigating = intent.getBooleanExtra("is_navigating", false);

                // Réinitialisation de l'affichage si aucun trajet n'est en cours
                if (!isNavigating) {
                    txtDescription.setText("Ouverture de Maps");
                    layoutDirection.setVisibility(View.GONE);
                    return;
                }

                // Extraction des données de navigation envoyées par le service
                String timeRemaining = intent.getStringExtra("time_remaining");
                String distanceRemaining = intent.getStringExtra("distance_remaining");
                String eta = intent.getStringExtra("eta");
                String nextDist = intent.getStringExtra("next_direction_distance");

                // Récupération de l'icône de manœuvre avec gestion de la compatibilité Android 13+ (Tiramisu)
                Icon turnIcon;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    turnIcon = intent.getParcelableExtra("turn_icon", Icon.class);
                } else {
                    turnIcon = intent.getParcelableExtra("turn_icon");
                }

                // Mise à jour visuelle des éléments de la carte
                layoutDirection.setVisibility(View.VISIBLE);
                txtDescription.setText(timeRemaining + " • " + distanceRemaining + " • " + eta);
                txtDirection.setText(nextDist);

                if (turnIcon != null) {
                    imageViewDirection.setImageIcon(turnIcon);
                }
            }
        }
    };

    /**
     * Constructeur utilisé lors de l'instanciation de la carte depuis un fichier de layout XML.
     * Inflate la vue, relie les composants graphiques et attache l'écouteur de clic.
     *
     * @param context Le contexte Android associatif.
     * @param attrs   Ensemble d'attributs XML passés au composant.
     */
    public CardMaps(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        LayoutInflater.from(context).inflate(R.layout.card_maps, this, true);

        txtDescription = findViewById(R.id.txtDescription);
        layoutDirection = findViewById(R.id.layoutDirection);
        imageViewDirection = findViewById(R.id.imageViewDirection);
        txtDirection = findViewById(R.id.txtDirection);

        View root = findViewById(R.id.card_maps_root);
        if (root != null) {
            root.setOnClickListener(this);
        } else {
            CarLog.e(TAG, "ID card_maps_root not found (CardMaps)");
        }
    }

    /**
     * Appelée lorsque la vue est attachée à une fenêtre.
     * Enregistre le récepteur de broadcasts pour écouter les événements de navigation Google Maps.
     */
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        IntentFilter filter = new IntentFilter(NotificationService.ACTION_MAPS_UPDATE);

        // Enregistrement sécurisé pour les applications cibles sous Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getContext().registerReceiver(mapsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            getContext().registerReceiver(mapsReceiver, filter);
        }
    }

    /**
     * Appelée lorsque la vue est détachée de sa fenêtre.
     * Désenregistre le récepteur de broadcasts pour éviter les fuites de mémoire.
     */
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        try {
            getContext().unregisterReceiver(mapsReceiver);
        } catch (IllegalArgumentException e) {
            // Ignoré si le récepteur n'était pas enregistré
        }
    }

    /**
     * Gère le clic sur la carte Google Maps.
     * Tente de lancer l'application officielle Google Maps sur le système.
     *
     * @param v La vue ayant reçu le clic.
     */
    @Override
    public void onClick(View v) {
        try {
            Intent launchIntent = getContext().getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(launchIntent);
            } else {
                CarLog.e(TAG, "Unable to open Google Maps: app is not installed.");
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error launching Google Maps: " + e.getMessage());
        }
    }
}