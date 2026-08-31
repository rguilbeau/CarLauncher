package com.rguilbeau.carlauncher.activity;

import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.rguilbeau.carlauncher.R;
import com.rguilbeau.carlauncher.selfupdate.GitHubUpdater;
import com.rguilbeau.carlauncher.selfupdate.UpdateListener;
import com.rguilbeau.carlauncher.utils.log.CarLog;
import com.rguilbeau.carlauncher.view.AppDrawerAdapter;
import com.rguilbeau.carlauncher.provider.apps.AppInfo;
import com.rguilbeau.carlauncher.provider.apps.AppProvider;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activité chargée d'afficher la grille complète des applications installées sur le système (App Drawer).
 * Gère le chargement asynchrone des applications pour éviter les blocages de l'interface graphique.
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
     * Configure les actions des boutons et lance le chargement asynchrone des applications.
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
            ProgressBar progressLoadingApps = findViewById(R.id.progressLoadingApps);

            if (rvApps != null && progressLoadingApps != null) {
                rvApps.setLayoutManager(new GridLayoutManager(this, 5));
                loadAppsAsynchronously(rvApps, progressLoadingApps);
            }

        } catch (Exception e) {
            CarLog.e(TAG, "Error initializing the AppDrawer", e);
        }
    }

    /**
     * Exécute la récupération des applications installées sur un thread d'arrière-plan,
     * puis met à jour l'interface utilisateur une fois le processus terminé.
     *
     * @param rvApps              Le composant RecyclerView devant recevoir la liste.
     * @param progressLoadingApps Le composant ProgressBar à masquer à la fin du chargement.
     */
    private void loadAppsAsynchronously(RecyclerView rvApps, ProgressBar progressLoadingApps) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Handler handler = new Handler(Looper.getMainLooper());

        executor.execute(() -> {
            try {
                // Opération lourde traitée en arrière-plan
                List<AppInfo> installedApps = AppProvider.getApps(AppDrawerActivity.this);

                // Retour sur le thread principal pour modifier l'interface
                handler.post(() -> {
                    AppDrawerAdapter adapter = new AppDrawerAdapter(AppDrawerActivity.this, installedApps);
                    rvApps.setAdapter(adapter);

                    progressLoadingApps.setVisibility(View.GONE);
                    rvApps.setVisibility(View.VISIBLE);
                });
            } catch (Exception e) {
                CarLog.e(TAG, "Error during asynchronous app retrieval", e);
            }
        });
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
             * Appelée lorsque le statut de l'opération change.
             *
             * @param status Le message de statut actuel.
             */
            @Override
            public void onStatusUpdate(String status) {
                statusText.setText(status);
            }

            /**
             * Appelée régulièrement pendant le téléchargement du fichier de mise à jour.
             *
             * @param progress Le pourcentage de progression.
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
             * Appelée en cas d'échec.
             *
             * @param error Le message décrivant l'erreur.
             */
            @Override
            public void onError(String error) {
                statusText.setText("Erreur : " + error);
                statusText.setTextColor(android.graphics.Color.RED);
                progressBar.setVisibility(View.GONE);
                dialog.setCancelable(true);
            }

            /**
             * Appelée lorsque le téléchargement est terminé.
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
     * Affiche l'activité permettant de lire les journaux d'événements (logs).
     */
    private void showLogViewer() {
        Intent intent = new Intent(AppDrawerActivity.this, LogViewerActivity.class);
        startActivity(intent);
    }
}