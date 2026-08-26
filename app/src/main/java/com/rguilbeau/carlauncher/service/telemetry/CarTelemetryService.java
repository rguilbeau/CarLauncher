package com.rguilbeau.carlauncher.service.telemetry;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Service centralisant la communication avec le système (MCU) de l'autoradio.
 * Capte, décode et distribue les événements du véhicule (état du contact, vitesse, régime moteur)
 * aux différents composants de l'application via le patron de conception Observateur (Observer Pattern).
 */
public class CarTelemetryService extends Service {

    /**
     * Interface de communication permettant aux composants liés d'interagir avec ce service.
     */
    private final IBinder binder = new LocalBinder();

    /**
     * Liste des écouteurs abonnés aux événements de télémétrie du véhicule.
     */
    private final List<CarTelemetryListener> listeners = new ArrayList<>();

    /**
     * État actuel du contact du véhicule. Initialisé à true par défaut.
     */
    private boolean isAccOn = true;

    /**
     * Dernière vitesse calculée du véhicule en km/h.
     */
    private int currentSpeed = 0;

    /**
     * Dernier régime moteur (RPM) calculé du véhicule en tours par minute.
     */
    private int currentRpm = 0;

    /**
     * Classe interne fournissant l'instance du service aux composants clients lors du binding.
     */
    public class LocalBinder extends Binder {
        /**
         * Retourne l'instance courante du CarTelemetryService.
         *
         * @return Le service de télémétrie actif.
         */
        public CarTelemetryService getService() {
            return CarTelemetryService.this;
        }
    }

    /**
     * Invoquée lorsqu'un autre composant veut se lier (bind) à ce service.
     *
     * @param intent L'intention utilisée pour se lier.
     * @return L'interface IBinder pour communiquer avec le service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    /**
     * Ajoute un nouvel abonné à la liste de diffusion des événements du véhicule.
     * Transmet immédiatement à ce nouvel abonné l'état actuel du contact et de la télémétrie.
     *
     * @param listener L'écouteur à ajouter.
     */
    public synchronized void addListener(CarTelemetryListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            listener.onAccStateChanged(isAccOn);
            listener.onTelemetryUpdated(currentSpeed, currentRpm);
        }
    }

    /**
     * Retire un abonné de la liste de diffusion.
     *
     * @param listener L'écouteur à retirer.
     */
    public synchronized void removeListener(CarTelemetryListener listener) {
        listeners.remove(listener);
    }

    /**
     * Récepteur d'intentions chargé de capter les diffusions (broadcasts) du système autoradio.
     * Gère les événements d'allumage/extinction (ACC) et délègue le traitement des données du bus CAN
     * aux méthodes spécialisées.
     */
    private final BroadcastReceiver carReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case "com.qf.action.ACC_ON":
                    handleAccOnEvent();
                    break;

                case "com.qf.action.ACC_OFF":
                    handleAccOffEvent();
                    break;

                case "com.qf.vehicle.action.DATA_SHARE":
                    handleDataShareEvent(intent);
                    break;
            }
        }
    };

    /**
     * Traite l'événement de mise sous tension du contact (ACC ON).
     * Met à jour l'état interne et notifie tous les abonnés.
     */
    private void handleAccOnEvent() {
        currentSpeed = 0;
        currentRpm = 0;
        notifyTelemetry(currentSpeed, currentRpm);

        isAccOn = true;
        notifyAccChanged(true);
    }

    /**
     * Traite l'événement de coupure du contact (ACC OFF).
     * Réinitialise les données de télémétrie (vitesse et RPM) à zéro, met à jour l'état interne
     * et notifie tous les abonnés de ces changements.
     */
    private void handleAccOffEvent() {
        isAccOn = false;
        notifyAccChanged(false);
    }

    /**
     * Traite la réception d'une trame de partage de données du véhicule.
     * Décode les octets du bus CAN (vitesse et RPM) ou récupère les valeurs simulées,
     * filtre les valeurs aberrantes (erreurs de trame) et notifie les abonnés.
     *
     * @param intent L'intention contenant le tableau d'octets de la télémétrie.
     */
    private void handleDataShareEvent(Intent intent) {
        byte[] data = intent.getByteArrayExtra("extra_DATA_SHARE");

        int newSpeed = currentSpeed;
        int newRpm = currentRpm;

        if (data != null && data.length > 10) {
            newSpeed = ((data[7] & 0xFF) << 8) | (data[8] & 0xFF);
            newRpm = ((data[9] & 0xFF) << 8) | (data[10] & 0xFF);
        } else {
            newSpeed = intent.getIntExtra("speed", currentSpeed);
            newRpm = intent.getIntExtra("rpm", currentRpm);
        }

        if (newSpeed < 10000) currentSpeed = newSpeed;
        if (newRpm < 10000) currentRpm = newRpm;

        notifyTelemetry(currentSpeed, currentRpm);
    }

    /**
     * Notifie tous les abonnés d'un changement d'état du contact du véhicule.
     *
     * @param accOn L'état du contact (true = allumé, false = coupé).
     */
    private synchronized void notifyAccChanged(boolean accOn) {
        for (CarTelemetryListener listener : listeners) {
            listener.onAccStateChanged(accOn);
        }
    }

    /**
     * Notifie tous les abonnés d'une mise à jour des données de vitesse et de régime moteur.
     *
     * @param speed La vitesse en km/h.
     * @param rpm   Le régime en tr/min.
     */
    private synchronized void notifyTelemetry(int speed, int rpm) {
        for (CarTelemetryListener listener : listeners) {
            listener.onTelemetryUpdated(speed, rpm);
        }
    }

    /**
     * Vérifie de manière sécurisée si le contact de la voiture est actuellement mis.
     *
     * @return true si le contact est allumé, false sinon.
     */
    public synchronized boolean isAccOn() {
        return isAccOn;
    }

    /**
     * Récupère de manière sécurisée la dernière vitesse enregistrée du véhicule.
     *
     * @return La vitesse en km/h.
     */
    public synchronized int getCurrentSpeed() {
        return currentSpeed;
    }

    /**
     * Récupère de manière sécurisée le dernier régime moteur enregistré du véhicule.
     *
     * @return Le régime en tr/min.
     */
    public synchronized int getCurrentRpm() {
        return currentRpm;
    }

    /**
     * Initialise le service.
     * Modifie les paramètres système pour forcer la réception des événements CAN et enregistre le récepteur.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        registerTelemetrySubscription();

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.qf.action.ACC_ON");
        filter.addAction("com.qf.action.ACC_OFF");
        filter.addAction("com.qf.vehicle.action.DATA_SHARE");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(carReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(carReceiver, filter);
        }
    }

    /**
     * Modifie les paramètres globaux (Settings.Global) d'Android pour inscrire spécifiquement
     * cette application sur la liste de diffusion de l'autoradio pour le partage des données de carrosserie.
     */
    private void registerTelemetrySubscription() {
        try {
            String pkgName = getPackageName();
            String pkgs = Settings.Global.getString(getContentResolver(), "KeyAllPackages");
            if (pkgs == null || pkgs.isEmpty()) {
                pkgs = pkgName;
            } else if (!pkgs.contains(pkgName)) {
                pkgs += "," + pkgName;
            }
            Settings.Global.putString(getContentResolver(), "KeyAllPackages", pkgs);
            Settings.Global.putInt(getContentResolver(), pkgName + "KeyShareCarbodyState", 1);
        } catch (Exception ignored) {
        }
    }

    /**
     * Nettoie les ressources et désenregistre le récepteur système à la destruction du service.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(carReceiver);
    }
}