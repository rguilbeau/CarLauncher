package com.rguilbeau.carlauncher.component.button_strategy;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.provider.apps.AppInfo;
import com.rguilbeau.carlauncher.provider.apps.AppProvider;

import java.util.List;

/**
 * Stratégie de comportement pour un bouton agissant comme un raccourci configurable.
 * <p>
 * <ul>
 *     <li><b>Clic simple :</b> Tente de récupérer et lancer le package de l'application mémorisée.</li>
 *     <li><b>Appui long :</b> Ouvre une liste avec icônes permettant d'assigner une application.</li>
 * </ul>
 * La récupération des applications est désormais déléguée à {@link AppProvider}.
 * </p>
 *
 * @author rguilbeau
 * @version 1.2
 */
public class ShortcutStrategy implements ButtonStrategy {

    /**
     * Tag d'identification utilisé pour les journaux d'erreurs (Logcat).
     */
    private static final String TAG = "ButtonShortcut";

    /**
     * Nom du fichier de préférences partagées.
     */
    private static final String PREFS_NAME = "CarLauncherPrefs";

    /**
     * Identifiant unique de ce raccourci, servant de clé dans les SharedPreferences.
     */
    private final String shortcutType;

    /**
     * Constructeur initialisant la stratégie de raccourci.
     *
     * @param shortcutType Le type de raccourci (ex: "navigation", "musique").
     */
    public ShortcutStrategy(String shortcutType) {
        this.shortcutType = shortcutType;
    }

    @Override
    public void onClick(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            String intentPackage = prefs.getString(shortcutType, "");

            if (intentPackage.isEmpty()) {
                Toast.makeText(context, "Aucune application assignée. Faites un appui long.", Toast.LENGTH_LONG).show();
                return;
            }

            PackageManager pm = context.getPackageManager();
            Intent launchIntent = pm.getLaunchIntentForPackage(intentPackage);

            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(launchIntent);
            } else {
                Toast.makeText(context, "Application non installée", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch application for shortcut type: " + shortcutType, e);
            Toast.makeText(context, "Erreur lors du lancement de l'application", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public boolean onLongClick(Context context) {
        showAppSelectionDialog(context);
        return true;
    }

    /**
     * Récupère la liste des applications via le provider mutualisé et affiche
     * une boîte de dialogue pour permettre la sélection et l'enregistrement.
     *
     * @param context Le contexte Android courant.
     */
    private void showAppSelectionDialog(Context context) {
        try {
            // 1. Récupération ultra-propre de la liste via notre classe utilitaire
            List<AppInfo> appList = AppProvider.getApps(context);

            if (appList.isEmpty()) {
                Toast.makeText(context, "Aucune application trouvée.", Toast.LENGTH_SHORT).show();
                return;
            }

            // 2. Création de l'adaptateur pour afficher l'icône et le nom dans l'AlertDialog
            ArrayAdapter<AppInfo> adapter = new ArrayAdapter<AppInfo>(context, R.layout.app_list_selection, appList) {
                @NonNull
                @Override
                public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                    if (convertView == null) {
                        convertView = LayoutInflater.from(context).inflate(R.layout.app_list_selection, parent, false);
                    }

                    AppInfo app = getItem(position);

                    ImageView imgIcon = convertView.findViewById(R.id.imgAppIcon);
                    TextView txtName = convertView.findViewById(R.id.txtAppName);

                    if (app != null) {
                        if (imgIcon != null) {
                            imgIcon.setImageDrawable(app.icon);
                        }
                        if (txtName != null) {
                            txtName.setText(app.name);
                        }
                    }

                    return convertView;
                }
            };

            // 3. Affichage de la boîte de dialogue
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle("Choisir une application")
                    .setAdapter(adapter, (dialogInterface, which) -> {
                        AppInfo selectedApp = appList.get(which);

                        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                        prefs.edit().putString(shortcutType, selectedApp.packageName).apply();

                        Toast.makeText(context, selectedApp.name + " assignée avec succès !", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Annuler", null)
                    .show();

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);

        } catch (Exception e) {
            Log.e(TAG, "Error while loading or displaying installed applications", e);
            Toast.makeText(context, "Impossible de charger la liste des applications", Toast.LENGTH_SHORT).show();
        }
    }
}