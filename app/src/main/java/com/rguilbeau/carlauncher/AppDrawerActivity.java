package com.rguilbeau.carlauncher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Activité chargée d'afficher la grille complète des applications installées sur le système (App Drawer).
 * Gère le chargement asynchrone des applications pour éviter les blocages de l'interface graphique.
 *
 * @author rguilbeau
 */
public class AppDrawerActivity extends AppCompatActivity implements SurfaceHolder.Callback {
//    /**
//     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
//     */
//    private static final String TAG = "AppDrawerActivity";
//
//    /**
//     * Initialise l'interface de la grille d'applications.
//     * Configure les actions des boutons de retour et de mise à jour,
//     * et charge la liste des applications installées dans le RecyclerView.
//     *
//     * @param savedInstanceState L'état précédemment sauvegardé de l'activité, si existant.
//     */
//    @Override
//    protected void onCreate(@Nullable Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState);
//        setContentView(R.layout.activity_app_drawer);
//
//        try {
//            ImageView btnBack = findViewById(R.id.btnBack);
//            if (btnBack != null) {
//                btnBack.setOnClickListener(v -> finish());
//            }
//
//            ImageView btnUpdate = findViewById(R.id.btnUpdate);
//            if (btnUpdate != null) {
//                btnUpdate.setOnClickListener(v -> showUpdateDialog());
//            }
//
//            RecyclerView rvApps = findViewById(R.id.rvApps);
//            if (rvApps != null) {
//                rvApps.setLayoutManager(new GridLayoutManager(this, 5));
//                List<AppInfo> installedApps = AppProvider.getApps(this);
//                AppDrawerAdapter adapter = new AppDrawerAdapter(this, installedApps);
//                rvApps.setAdapter(adapter);
//            }
//
//        } catch (Exception e) {
//            Log.e(TAG, "Error initializing AppDrawer UI", e);
//        }
//    }
//
//    /**
//     * Affiche une boîte de dialogue pour suivre la progression de la mise à jour système via GitHub.
//     * Construit l'interface programmatiquement et écoute les événements de téléchargement.
//     */
//    private void showUpdateDialog() {
//        LinearLayout layout = new LinearLayout(this);
//        layout.setOrientation(LinearLayout.VERTICAL);
//        int padding = 50;
//        layout.setPadding(padding, padding, padding, padding);
//
//        TextView statusText = new TextView(this);
//        statusText.setText("Connexion à GitHub...");
//        statusText.setTextSize(16f);
//        statusText.setPadding(0, 0, 0, 30);
//        layout.addView(statusText);
//
//        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
//        progressBar.setMax(100);
//        progressBar.setIndeterminate(true);
//        layout.addView(progressBar);
//
//        AlertDialog dialog = new AlertDialog.Builder(this)
//                .setTitle("Mise à jour système")
//                .setView(layout)
//                .setCancelable(false)
//                .setNegativeButton("Fermer", (d, which) -> d.dismiss())
//                .show();
//
//        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.WHITE);
//        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(Color.WHITE);
//        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.WHITE);
//
//        GitHubUpdater updater = new GitHubUpdater(this, new UpdateListener() {
//
//            /**
//             * Appelée lorsque le statut de l'opération change (ex: recherche d'une nouvelle version, début du téléchargement).
//             * Met à jour le texte principal de la boîte de dialogue.
//             *
//             * @param status Le message de statut actuel.
//             */
//            @Override
//            public void onStatusUpdate(String status) {
//                statusText.setText(status);
//            }
//
//            /**
//             * Appelée régulièrement pendant le téléchargement du fichier de mise à jour.
//             * Fait passer la barre de progression en mode déterminé (si besoin) et affiche le pourcentage d'avancement.
//             *
//             * @param progress Le pourcentage de progression (de 0 à 100).
//             */
//            @Override
//            public void onProgress(int progress) {
//                if (progressBar.isIndeterminate()) {
//                    progressBar.setIndeterminate(false);
//                }
//                progressBar.setProgress(progress);
//                statusText.setText("Téléchargement : " + progress + "%");
//            }
//
//            /**
//             * Appelée en cas d'échec (problème réseau, fichier introuvable, erreur d'écriture).
//             * Affiche le message d'erreur en rouge, masque la barre de progression et permet à l'utilisateur de fermer la fenêtre.
//             *
//             * @param error Le message décrivant l'erreur.
//             */
//            @Override
//            public void onError(String error) {
//                statusText.setText("Erreur : " + error);
//                statusText.setTextColor(android.graphics.Color.RED);
//                progressBar.setVisibility(android.view.View.GONE);
//                dialog.setCancelable(true);
//            }
//
//            /**
//             * Appelée lorsque le téléchargement est terminé et que la mise à jour est prête.
//             * Affiche un message de succès, remplit la jauge à 100% et déverrouille la fermeture de la fenêtre.
//             */
//            @Override
//            public void onSuccess() {
//                statusText.setText("Mise à jour installée !");
//                progressBar.setProgress(100);
//                dialog.setCancelable(true);
//            }
//        });
//
//        updater.update();
//    }


    private static final String TAG = "VirtualDisplayMaps";
    private DisplayManager displayManager;
    private VirtualDisplay virtualDisplay;
    private SurfaceView surfaceView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_drawer);

        surfaceView = findViewById(R.id.maps_surface_view);
        surfaceView.getHolder().addCallback(this);

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
    }
    private static final int VIRTUAL_DISPLAY_FLAG_TRUSTED = 1 << 10; // 1024
    @SuppressWarnings("WrongConstant")
    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        // 1. Récupérer les dimensions exactes de la vue
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        DisplayMetrics metrics = getResources().getDisplayMetrics();

        Log.d(TAG, "Création du VirtualDisplay : " + width + "x" + height);

        int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

//try {
    virtualDisplay = displayManager.createVirtualDisplay(
            "MapsVirtualDisplay",
            width,
            height,
            metrics.densityDpi,
            holder.getSurface(),
            flags
    );

    if (virtualDisplay != null) {
        int displayId = virtualDisplay.getDisplay().getDisplayId();
        Log.d("PrivApp", "VirtualDisplay créé avec succès, ID: " + displayId);


        new Thread(() -> {
            try {
                // Commande pour envoyer l'ordre am via le socket ADB local
                String cmd = "adb shell am start -n com.google.android.apps.maps/com.google.android.maps.MapsActivity --display " + displayId;
                Runtime.getRuntime().exec(cmd);
            } catch (Exception e) {
                Log.e("ADB_LOCAL", "Erreur d'exécution", e);
            }
        }).start();

    }
//
//        // Lancer Maps via l'API standard Android (plus besoin de shell ou exec)
//        Intent intent = new Intent();
//        intent.setClassName("com.google.android.apps.maps", "com.google.android.maps.MapsActivity");
//        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//
//        ActivityOptions options = ActivityOptions.makeBasic();
//        options.setLaunchDisplayId(displayId);
//
//        startActivity(intent, options.toBundle());
//    }
//}
//catch (Exception e)
//{
//    int i = 65;
//}

    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
    }
}