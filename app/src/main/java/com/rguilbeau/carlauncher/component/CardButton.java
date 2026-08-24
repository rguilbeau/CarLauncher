package com.rguilbeau.carlauncher.component;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.component.button_strategy.AppDrawerStrategy;
import com.rguilbeau.carlauncher.component.button_strategy.ButtonStrategy;
import com.rguilbeau.carlauncher.component.button_strategy.DayNightStrategy;
import com.rguilbeau.carlauncher.component.button_strategy.ShortcutStrategy;

/**
 * Composant d'interface utilisateur autonome offrant une carte cliquable et personnalisable.
 * <p>
 * Ce composant délègue l'intégralité de son comportement (clic simple et appui long) à une interface
 * {@link ButtonStrategy}, implémentant ainsi le <b>Patron de conception Stratégie (Strategy Pattern)</b>.
 * Le choix de la stratégie est déterminé à l'instanciation via l'attribut XML "type".
 * </p>
 *
 * @author rguilbeau
 * @version 1.0
 */
public class CardButton extends FrameLayout implements View.OnClickListener, View.OnLongClickListener {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs et de débogage (Logcat).
     */
    private static final String TAG = "CardButton";

    /**
     * Stratégie de comportement affectée à ce bouton spécifique.
     * Elle définit les actions à exécuter lors des interactions utilisateur.
     */
    private ButtonStrategy buttonStrategy;

    /**
     * Constructeur utilisé lors de l'instanciation de la vue depuis un fichier de layout XML.
     * Lit les attributs personnalisés ("type", "cardColor", "cardIcon"), instancie la stratégie
     * correspondante et applique le style visuel.
     *
     * @param context Le contexte Android associé à l'environnement d'exécution.
     * @param attrs   Ensemble d'attributs XML passés au composant lors de son gonflage.
     */
    @SuppressLint("CutPasteId")
    public CardButton(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);

        // Inflation du layout XML définissant la structure visuelle interne de la carte
        LayoutInflater.from(context).inflate(R.layout.card_button, this, true);

        String type = "";
        String title = "";
        String description = "";
        int cardColor = 0;
        int cardIconResId = 0;

        // Lecture et extraction des attributs déclarés directement dans le layout XML
        if (attrs != null) {
            try {
                TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.CardButton);

                type = a.getString(R.styleable.CardButton_type);
                title = a.getString(R.styleable.CardButton_cardTitle);
                description = a.getString(R.styleable.CardButton_cardDescription);
                cardColor = a.getColor(R.styleable.CardButton_cardColor, 0);
                cardIconResId = a.getResourceId(R.styleable.CardButton_cardIcon, 0);

                a.recycle();
            } catch (Exception e) {
                Log.e(TAG, "Error extracting custom attributes from XML", e);
            }
        }

        // Application dynamique des styles et contenus textuels sur les composants enfants
        try {
            androidx.cardview.widget.CardView buttonRoot = findViewById(R.id.card_root);
            android.widget.ImageView buttonIcon = findViewById(R.id.button_icon);
            TextView txtTitle = findViewById(R.id.button_title);
            TextView txtDescription = findViewById(R.id.button_description);

            if (buttonRoot != null && cardColor != 0) {
                buttonRoot.setCardBackgroundColor(cardColor);
            }

            if (buttonIcon != null && cardIconResId != 0) {
                buttonIcon.setImageResource(cardIconResId);
            }

            if (txtTitle != null && title != null) {
                txtTitle.setText(title);
            }

            if (txtDescription != null && description != null) {
                txtDescription.setText(description);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error applying visual attributes to CardButton views", e);
        }

        // Sécurisation de la chaîne pour éviter les NullPointerException lors du contrôle du type
        if (type == null) {
            type = "";
        }

        // Instanciation de la stratégie métier selon le type spécifié dans le XML
        try {
            if (AppDrawerStrategy.TYPE.equals(type)) {
                this.buttonStrategy = new AppDrawerStrategy();
            } else if (DayNightStrategy.TYPE.equals(type)) {
                this.buttonStrategy = new DayNightStrategy();
            } else if (!type.isEmpty()) {
                this.buttonStrategy = new ShortcutStrategy(type);
            } else {
                throw new RuntimeException("Button type not defined in XML configuration");
            }

            // Attachement des écouteurs sur la vue racine pour déclencher l'effet visuel de clic (ripple/foreground)
            View root = findViewById(R.id.card_root);
            if (root != null) {
                root.setOnClickListener(this);
                root.setOnLongClickListener(this);
            } else {
                Log.e(TAG, "ID card_root not found (CardButton)");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during button strategy initialization", e);
        }
    }

    /**
     * Intercepte le clic simple sur la carte et délègue l'exécution à la stratégie configurée.
     *
     * @param v La vue qui a été cliquée.
     */
    @Override
    public void onClick(View v) {
        if (buttonStrategy != null) {
            buttonStrategy.onClick(getContext());
        }
    }

    /**
     * Intercepte l'appui long sur la carte et délègue l'exécution à la stratégie configurée.
     *
     * @param v La vue ayant reçu l'appui long.
     * @return true si l'événement a été consommé par la stratégie, false sinon.
     */
    @Override
    public boolean onLongClick(View v) {
        if (buttonStrategy != null) {
            return buttonStrategy.onLongClick(getContext());
        }

        return false;
    }
}