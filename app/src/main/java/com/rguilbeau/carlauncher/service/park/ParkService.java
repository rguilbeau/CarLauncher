package com.rguilbeau.carlauncher.service.park;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.NonNull;

import com.rguilbeau.carlauncher.manager.PermissionManager;
import com.rguilbeau.carlauncher.repository.CarLocationRepository;
import com.rguilbeau.carlauncher.repository.dto.CarLocation;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryListener;
import com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService;
import com.rguilbeau.carlauncher.utils.log.CarLog;

/**
 * Service d'arrière-plan chargé d'enregistrer la dernière position connue du véhicule lorsqu'il
 * se gare.
 * <p>
 * S'abonne au {@link CarTelemetryService} pour détecter le franchissement à la baisse du seuil
 * de vitesse ({@link #MIN_SPEED_KMH}), déclencheur principal de la sauvegarde. La coupure du
 * contact (ACC_OFF) agit comme filet de sécurité (fallback) au cas où la vitesse n'aurait pas
 * transité sous ce seuil avant l'arrêt du véhicule.
 * </p>
 */
public class ParkService extends Service implements LocationListener, CarTelemetryListener {

    /**
     * Tag utilisé pour l'identification des messages de journalisation de ce service.
     */
    private static final String TAG = "ParkService";

    /**
     * Vitesse minimale (en km/h) issue du bus CAN en dessous de laquelle le véhicule est considéré
     * comme garé.
     */
    private static final float MIN_SPEED_KMH = 2.0f;

    /**
     * Rayon maximal d'imprécision (en mètres) toléré par le capteur GPS.
     */
    private static final float MAX_ACCURACY_M = 20.0f;

    /**
     * Gestionnaire système Android fournissant les mises à jour de la localisation géographique.
     */
    private LocationManager locationManager;

    /**
     * Conserve en mémoire la dernière position GPS valide connue du véhicule.
     */
    private Location lastLocation = null;

    /**
     * Dernière vitesse connue transmise par le bus CAN, utilisée pour détecter le franchissement
     * à la baisse du seuil {@link #MIN_SPEED_KMH}.
     */
    private int lastSpeedKmH = -1;

    /**
     * Référence vers le service central de télémétrie de la voiture.
     */
    private CarTelemetryService telemetryService;

    /**
     * Indicateur d'état précisant si le ParkService est actuellement attaché au service de télémétrie.
     */
    private boolean isBound = false;

    /**
     * Gère le cycle de vie de la connexion avec le service de télémétrie.
     */
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        /**
         * Récupère l'instance du service de télémétrie et s'y abonne.
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CarTelemetryService.LocalBinder binder = (CarTelemetryService.LocalBinder) service;
            telemetryService = binder.getService();
            telemetryService.addListener(ParkService.this);
            CarLog.d(TAG, "ParkService connected to CANbus.");
        }

        /**
         * Oublie la référence au service de télémétrie devenue invalide.
         */
        @Override
        public void onServiceDisconnected(ComponentName name) {
            telemetryService = null;
        }
    };

    /**
     * Initialise le service : se lie au service de télémétrie et démarre les mises à jour GPS si
     * la permission de localisation est accordée.
     */
    @SuppressLint("MissingPermission")
    @Override
    public void onCreate() {
        super.onCreate();

        Intent intent = new Intent(this, CarTelemetryService.class);
        isBound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        try {
            locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null && PermissionManager.hasLocationPermission(this)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error initializing GPS", e);
        }
    }

    /**
     * Détecte le franchissement à la baisse du seuil {@link #MIN_SPEED_KMH} (front descendant) et
     * déclenche la sauvegarde de la dernière position connue.
     *
     * @param speed La vitesse actuelle du véhicule en km/h.
     * @param rpm   Le régime moteur actuel, non utilisé par ce service.
     */
    @Override
    public void onTelemetryUpdated(int speed, int rpm) {
        boolean wasMoving = lastSpeedKmH < 0 || lastSpeedKmH >= MIN_SPEED_KMH;
        boolean isBelowThreshold = speed < MIN_SPEED_KMH;

        if (wasMoving && isBelowThreshold) {
            CarLog.i(TAG, "Speed dropped below " + MIN_SPEED_KMH + " km/h, saving car location.");
            saveLastKnownLocation();
        }

        lastSpeedKmH = speed;
    }

    /**
     * Filet de sécurité (fallback) : sauvegarde la dernière position connue à la coupure du contact,
     * au cas où la vitesse n'aurait pas transité sous {@link #MIN_SPEED_KMH} avant l'arrêt.
     *
     * @param accOn true si le contact est mis, false sinon.
     */
    @Override
    public void onAccStateChanged(boolean accOn) {
        if (!accOn) {
            CarLog.i(TAG, "Ignition off (ACC_OFF), saving car location as fallback.");
            saveLastKnownLocation();
        }
    }

    /**
     * Enregistre la dernière position GPS connue via {@link CarLocationRepository}, si disponible.
     */
    private void saveLastKnownLocation() {
        if (lastLocation == null) {
            CarLog.w(TAG, "No GPS location available yet, save skipped.");
            return;
        }

        CarLocationRepository.get().setLocation(new CarLocation(lastLocation.getLongitude(), lastLocation.getLatitude()));
    }

    /**
     * Met à jour la dernière position GPS connue, en filtrant les relevés jugés trop imprécis.
     *
     * @param location L'objet Location contenant les nouvelles coordonnées.
     */
    @Override
    public void onLocationChanged(@NonNull Location location) {
        if (!location.hasAccuracy() || location.getAccuracy() > MAX_ACCURACY_M) return;

        lastLocation = location;
    }

    /**
     * Demande à Android de redémarrer le service (sans réintention) s'il venait à être tué.
     *
     * @return {@link #START_STICKY}.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    /**
     * Libère les ressources à l'arrêt du service : désabonnement du service de télémétrie
     * et arrêt des mises à jour GPS.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            if (isBound) {
                if (telemetryService != null) {
                    telemetryService.removeListener(this);
                }
                unbindService(serviceConnection);
                isBound = false;
            }

            if (locationManager != null) {
                locationManager.removeUpdates(this);
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur nettoyage onDestroy", e);
        }
    }

    /**
     * Aucun composant ne se lie à ce service : il n'expose pas de binder.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Non utilisé : le statut du fournisseur GPS n'a pas d'impact sur la sauvegarde de position.
     */
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    /**
     * Non utilisé : l'activation du fournisseur GPS n'a pas d'impact sur la sauvegarde de position.
     */
    @Override
    public void onProviderEnabled(@NonNull String provider) {
    }

    /**
     * Non utilisé : la désactivation du fournisseur GPS n'a pas d'impact sur la sauvegarde de position.
     */
    @Override
    public void onProviderDisabled(@NonNull String provider) {
    }
}
