package com.rguilbeau.carlauncher;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;

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
import com.rguilbeau.carlauncher.utils.log.CarLog;
import com.rguilbeau.carlauncher.view.AppDrawerAdapter;
import com.rguilbeau.carlauncher.provider.apps.AppInfo;
import com.rguilbeau.carlauncher.provider.apps.AppProvider;

import java.util.List;

/**
 * Activité chargée d'afficher la grille complète des applications installées sur le système (App Drawer).
 *
 * @author rguilbeau
 */
public class AppDrawerActivity extends AppCompatActivity {
    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "AppDrawerActivity";

    /**
     * Initialise l'interface de la grille d'applications.
     * Configure les actions des boutons de retour et de mise à jour,
     * et charge la liste des applications installées dans le RecyclerView.
     *
     * @param savedInstanceState L'état précédemment sauvegardé de l'activité, si existant.
     */
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_drawer);

        try {
            ImageView btnBack = findViewById(R.id.btnBack);
            if (btnBack != null) {
                btnBack.setOnClickListener(v -> finish());
            }

            ImageView btnUpdate = findViewById(R.id.btnUpdate);
            if (btnUpdate != null) {
                btnUpdate.setOnClickListener(v -> showUpdateDialog());
            }

            ImageView btnLogViewer = findViewById(R.id.btnLogViewer);
            if (btnLogViewer != null) {
                btnLogViewer.setOnClickListener(v -> showLogViewer());
            }

            RecyclerView rvApps = findViewById(R.id.rvApps);
            if (rvApps != null) {
                rvApps.setLayoutManager(new GridLayoutManager(this, 5));
                List<AppInfo> installedApps = AppProvider.getApps(this);
                AppDrawerAdapter adapter = new AppDrawerAdapter(this, installedApps);
                rvApps.setAdapter(adapter);
            }

        } catch (Exception e) {
            CarLog.e(TAG, "Error initializing AppDrawer UI", e);
        }
    }

    /**
     * Affiche une boîte de dialogue pour suivre la progression de la mise à jour système via GitHub.
     * Construit l'interface programmatiquement et écoute les événements de téléchargement.
     */
    private void showUpdateDialog() {
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

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Mise à jour système")
                .setView(layout)
                .setCancelable(false)
                .setNegativeButton("Fermer", (d, which) -> d.dismiss())
                .show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);

        GitHubUpdater updater = new GitHubUpdater(this, new UpdateListener() {

            /**
             * Appelée lorsque le statut de l'opération change (ex: recherche d'une nouvelle version, début du téléchargement).
             * Met à jour le texte principal de la boîte de dialogue.
             *
             * @param status Le message de statut actuel.
             */
            @Override
            public void onStatusUpdate(String status) {
                statusText.setText(status);
            }

            /**
             * Appelée régulièrement pendant le téléchargement du fichier de mise à jour.
             * Fait passer la barre de progression en mode déterminé (si besoin) et affiche le pourcentage d'avancement.
             *
             * @param progress Le pourcentage de progression (de 0 à 100).
             */
            @Override
            public void onProgress(int progress) {
                if (progressBar.isIndeterminate()) {
                    progressBar.setIndeterminate(false);
                }
                progressBar.setProgress(progress);
                statusText.setText("Téléchargement : " + progress + "%");
            }

            /**
             * Appelée en cas d'échec (problème réseau, fichier introuvable, erreur d'écriture).
             * Affiche le message d'erreur en rouge, masque la barre de progression et permet à l'utilisateur de fermer la fenêtre.
             *
             * @param error Le message décrivant l'erreur.
             */
            @Override
            public void onError(String error) {
                statusText.setText("Erreur : " + error);
                statusText.setTextColor(android.graphics.Color.RED);
                progressBar.setVisibility(android.view.View.GONE);
                dialog.setCancelable(true);
            }

            /**
             * Appelée lorsque le téléchargement est terminé et que la mise à jour est prête.
             * Affiche un message de succès, remplit la jauge à 100% et déverrouille la fermeture de la fenêtre.
             */
            @Override
            public void onSuccess() {
                statusText.setText("Mise à jour installée !");
                progressBar.setProgress(100);
                dialog.setCancelable(true);
            }
        });

        updater.update();
    }

    /**
     * Affiche l'activité permettant de lire les logs de l'application
     */
    private void showLogViewer() {
        Intent intent = new Intent(AppDrawerActivity.this, LogViewerActivity.class);
        startActivity(intent);
    }
}