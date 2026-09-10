package com.rguilbeau.carlauncher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.rguilbeau.carlauncher.manager.AutoPlayManager;
import com.rguilbeau.carlauncher.manager.PermissionManager;
import com.rguilbeau.carlauncher.service.ignition.IgnitionService;
import com.rguilbeau.carlauncher.service.ignition.IgnitionListener;
import com.rguilbeau.carlauncher.service.TripService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

/**
 * Activité principale du Car Launcher.
 * Gère l'initialisation de l'interface, la gestion des permissions
 * et l'écoute des événements du véhicule (état du contact et réveil d'écran).
 */
public class MainActivity extends AppCompatActivity implements IgnitionListener {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "MainActivity";

    /**
     * Gestionnaire responsable de l'initialisation et du lancement automatique de l'application musicale au démarrage.
     */
    private AutoPlayManager autoPlayManager;

    /**
     * Service lié permettant de connaître l'état du contact (ACC) du véhicule.
     */
    private IgnitionService ignitionService;

    /**
     * Indicateur permettant de savoir si l'activité est actuellement connectée (bind) au service d'ignition.
     */
    private boolean ignitionServiceBound = false;

    /**
     * Intercepte l'événement de réveil de l'écran (ACTION_SCREEN_ON).
     * Simule un appui sur le bouton Home pour forcer l'affichage du Launcher.
     */
    private final BroadcastReceiver screenWakeUpReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())) {
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(homeIntent);
            }
        }
    };

    /**
     * Gère la connexion avec le service d'état du contact du véhicule (IgnitionService).
     * S'abonne aux changements d'ACC une fois le service connecté.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            IgnitionService.LocalBinder binder = (IgnitionService.LocalBinder) service;
            ignitionService = binder.getService();
            // L'abonnement déclenche instantanément onAccStateChanged(true) au démarrage
            ignitionService.addListener(MainActivity.this);
            ignitionServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            ignitionServiceBound = false;
            ignitionService = null;
        }
    };

    /**
     * Initialise l'activité au démarrage.
     * Configure l'interface, les gestionnaires, la connexion au bus CAN et les permissions GPS.
     *
     * @param savedInstanceState L'état précédemment sauvegardé de l'activité, si existant.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        hideSystemUI();
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        autoPlayManager = new AutoPlayManager(this);

        // Connexion au service d'ignition pour écouter l'allumage du contact
        Intent intent = new Intent(this, IgnitionService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (PermissionManager.hasLocationPermission(this)) {
            startTripService();
        } else {
            PermissionManager.requestLocationPermission(this);
        }

        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenWakeUpReceiver, filter);
    }

    /**
     * Déclenchée lors d'un changement d'état du contact du véhicule (ACC).
     *
     * @param isAccOn true si le contact est mis, false sinon.
     */
    @Override
    public void onAccStateChanged(boolean isAccOn) {
        if (isAccOn) {
            CarLog.i(TAG, "Ignition ON (ACC_ON) — Launching Autoplay");
            if (autoPlayManager != null) {
                autoPlayManager.startAutoplayDelayed();
            }
        }
    }

    /**
     * Appelée lorsque l'activité revient au premier plan.
     * Réapplique le mode immersif et vérifie les permissions de notifications.
     */
    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();

        if (!PermissionManager.hasNotificationPermission(this)) {
            Toast.makeText(this, "Veuillez autoriser l'accès aux notifications pour la musique", Toast.LENGTH_LONG).show();
            PermissionManager.openNotificationSettings(this);
        }
    }

    /**
     * Démarre le service d'enregistrement des trajets (TripService).
     */
    private void startTripService() {
        try {
            Intent intent = new Intent(this, TripService.class);
            startService(intent);
        } catch (Exception e) {
            CarLog.e(TAG, "Failed to start TripService", e);
        }
    }

    /**
     * Gère la réponse de l'utilisateur aux demandes de permissions système.
     *
     * @param requestCode  Le code de requête passé lors de la demande.
     * @param permissions  Les permissions demandées.
     * @param grantResults Les résultats (accordé ou refusé) pour chaque permission.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        PermissionManager.handlePermissionResult(requestCode, grantResults, new PermissionManager.PermissionCallback() {
            @Override
            public void onGranted() {
                startTripService();
                recreate();
            }

            @Override
            public void onDenied() {
                Toast.makeText(MainActivity.this, "Permission GPS requise pour le fonctionnement optimal", Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Cache la barre de navigation et la barre d'état pour maintenir un mode plein écran immersif.
     */
    private void hideSystemUI() {
        try {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            );
        } catch (Exception e) {
            CarLog.e(TAG, "Error applying immersive mode", e);
        }
    }

    /**
     * Nettoie les ressources lors de la destruction de l'activité.
     * Désabonne les écouteurs, détache les services et annule l'enregistrement des receivers.
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Nettoyage des listeners et receivers pour éviter les fuites de mémoire
        if (ignitionServiceBound && ignitionService != null) {
            ignitionService.removeListener(this);
            unbindService(serviceConnection);
            ignitionServiceBound = false;
        }
        if (autoPlayManager != null) {
            autoPlayManager.stop();
        }

        unregisterReceiver(screenWakeUpReceiver);
    }
}