package com.rguilbeau.carlauncher;

import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.rguilbeau.carlauncher.selfupdate.GitHubUpdater;
import com.rguilbeau.carlauncher.selfupdate.UpdateListener;
import com.rguilbeau.carlauncher.view.AppDrawerAdapter;
import com.rguilbeau.carlauncher.provider.apps.AppInfo;
import com.rguilbeau.carlauncher.provider.apps.AppProvider;

import java.util.List;

/**
 * Activité chargée d'afficher la grille complète des applications installées sur le système (App Drawer).
 *
 * @author rguilbeau
 * @version 1.3
 */
public class AppDrawerActivity extends AppCompatActivity {

    private static final String TAG = "AppDrawerActivity";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_drawer);

        try {
            // 1. Gestion du bouton retour
            ImageView btnBack = findViewById(R.id.btnBack);
            if (btnBack != null) {
                // finish() ferme l'activité en cours et ramène l'utilisateur sur le Launcher
                btnBack.setOnClickListener(v -> finish());
            }

            // 2. Gestion du bouton de mise à jour (Via GitHubUpdater)
            ImageView btnUpdate = findViewById(R.id.btnUpdate);
            if (btnUpdate != null) {
                btnUpdate.setOnClickListener(v -> showUpdateDialog());
            }

            // 3. Configuration de la grille des applications
            RecyclerView rvApps = findViewById(R.id.rvApps);

            // Création d'une grille avec 5 colonnes (idéal pour un écran horizontal)
            if (rvApps != null) {
                rvApps.setLayoutManager(new GridLayoutManager(this, 5));

                // Récupération de la liste mutualisée via le Provider
                List<AppInfo> installedApps = AppProvider.getApps(this);

                // Liaison à l'adaptateur
                AppDrawerAdapter adapter = new AppDrawerAdapter(this, installedApps);
                rvApps.setAdapter(adapter);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error initializing AppDrawer UI", e);
        }
    }

    private void showUpdateDialog() {
        // 1. Création de l'interface du popup en Java
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = 50;
        layout.setPadding(padding, padding, padding, padding);

        TextView statusText = new TextView(this);
        statusText.setText("Connexion à GitHub...");
        statusText.setTextSize(16f);
        statusText.setPadding(0, 0, 0, 30);
        layout.addView(statusText);

        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(true);
        layout.addView(progressBar);

        // 2. Création et affichage de la boîte de dialogue
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Mise à jour système")
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton("Fermer", (d, which) -> d.dismiss())
                .show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);

        // 3. Lancement de la mise à jour
        GitHubUpdater updater = new GitHubUpdater(this, new UpdateListener() {
            @Override
            public void onStatusUpdate(String status) {
                statusText.setText(status);
            }

            @Override
            public void onProgress(int progress) {
                if (progressBar.isIndeterminate()) {
                    progressBar.setIndeterminate(false);
                }
                progressBar.setProgress(progress);
                statusText.setText("Téléchargement : " + progress + "%");
            }

            @Override
            public void onError(String error) {
                statusText.setText("Erreur : " + error);
                statusText.setTextColor(android.graphics.Color.RED);
                progressBar.setVisibility(android.view.View.GONE);
                dialog.setCancelable(true);
            }

            @Override
            public void onSuccess() {
                statusText.setText("Mise à jour installée !");
                progressBar.setProgress(100);
                dialog.setCancelable(true); // Autorise la fermeture du popup
            }
        });

        updater.update();
    }
}