package com.rguilbeau.carlauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.rguilbeau.carlauncher.utils.log.CarLog;
import com.rguilbeau.carlauncher.utils.root.RootCommands;

/**
 * Activité chargée d'afficher la grille complète des applications installées sur le système (App Drawer).
 * Intègre Google Maps dans un VirtualDisplay persistant qui bascule sur une surface hors-écran
 * lorsque l'activité perd la main.
 *
 * @author rguilbeau
 */
public class AppDrawerActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String TAG = "VirtualDisplayMaps";

    private DisplayManager displayManager;
    private VirtualDisplay virtualDisplay;
    private SurfaceView surfaceView;

    // Surface neutre d'arrière-plan pour maintenir Maps actif sans saut sur le Display 0
    private Surface dummySurface;
    private SurfaceTexture dummySurfaceTexture;
    private int virtualDisplayId = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_drawer);

        surfaceView = findViewById(R.id.maps_surface_view);
        surfaceView.getHolder().addCallback(this);

        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        int width = surfaceView.getWidth();
        int height = surfaceView.getHeight();
        DisplayMetrics metrics = getResources().getDisplayMetrics();

        // FORCER le buffer de la surface aux dimensions exactes du SurfaceView
        holder.setFixedSize(width, height);

        CarLog.d(TAG, "surfaceCreated : " + width + "x" + height);

        setupOrUpdateVirtualDisplay(holder.getSurface(), width, height, metrics.densityDpi);
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        CarLog.d(TAG, "surfaceDestroyed : redirection du VirtualDisplay vers la surface neutre");

        // L'utilisateur quitte le Launcher (ex: ouvre Settings) :
        // On ne détruit PAS le VirtualDisplay, on réoriente sa surface vers le buffer neutre.
        if (virtualDisplay != null && dummySurface != null) {
            virtualDisplay.setSurface(dummySurface);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupOrUpdateVirtualDisplay(Surface realSurface, int width, int height, int densityDpi) {
        try {
            // 1. Initialisation de la surface neutre de secours si nécessaire
            if (dummySurface == null) {
                dummySurfaceTexture = new SurfaceTexture(10);
                dummySurfaceTexture.setDefaultBufferSize(width, height);
                dummySurface = new Surface(dummySurfaceTexture);
            }

            // 2. Première création du VirtualDisplay
            if (virtualDisplay == null) {
                int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;

                virtualDisplay = displayManager.createVirtualDisplay(
                        "MapsVirtualDisplay",
                        width,
                        height,
                        densityDpi,
                        realSurface,
                        flags
                );

                if (virtualDisplay != null) {
                    virtualDisplayId = virtualDisplay.getDisplay().getDisplayId();
                    CarLog.d(TAG, "VirtualDisplay créé avec succès, ID: " + virtualDisplayId);

                    // Configuration des événements tactiles vers le Daemon Root
                    surfaceView.setOnTouchListener(new View.OnTouchListener() {
                        @Override
                        public boolean onTouch(View v, MotionEvent event) {
                            if (event.getAction() == MotionEvent.ACTION_UP) {
                                v.performClick();
                            }
                            RootCommands.sendTouchToRootService(event, virtualDisplayId);
                            return true;
                        }
                    });

                    // Lancement de Maps sur le Virtual Display
                    RootCommands.launchMapsOnDisplay(virtualDisplayId);
                }
            } else {
                // 3. Si le VirtualDisplay existe déjà, on lui réassocie la vraie surface visuelle
                CarLog.d(TAG, "Réassociation du VirtualDisplay à la SurfaceView");
                virtualDisplay.setSurface(realSurface);

                RootCommands.launchMapsOnDisplay(virtualDisplayId);
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur lors du paramétrage du VirtualDisplay", e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Libération définitive des ressources uniquement à la destruction complète de l'Activité
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (dummySurface != null) {
            dummySurface.release();
            dummySurface = null;
        }
        if (dummySurfaceTexture != null) {
            dummySurfaceTexture.release();
            dummySurfaceTexture = null;
        }
    }
}