package com.rguilbeau.carlauncher.service.ignition;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Service dédié à l'état du contact (ignition) du véhicule.
 * <p>
 * Capte les broadcasts système {@code com.qf.action.ACC_ON} / {@code com.qf.action.ACC_OFF} émis
 * par l'autoradio et notifie les composants abonnés via le patron de conception Observateur.
 * <p>
 * Séparé de {@link com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService}, qui ne gère
 * que la télémétrie temps réel (vitesse, régime moteur, kilométrage) via le client AIDL du bus CAN.
 */
public class IgnitionService extends Service {

    /**
     * Tag utilisé pour l'identification des messages de journalisation de ce service.
     */
    private static final String TAG = "IgnitionService";

    /**
     * Interface de communication permettant aux composants liés d'interagir avec ce service.
     */
    private final IBinder binder = new LocalBinder();

    /**
     * Liste des écouteurs abonnés aux changements d'état du contact.
     */
    private final List<IgnitionListener> listeners = new ArrayList<>();

    /**
     * État actuel du contact du véhicule. Initialisé à true par défaut.
     */
    private boolean isAccOn = true;

    /**
     * Récepteur d'intentions chargé de capter les diffusions (broadcasts) d'allumage/extinction
     * du contact émises par le système autoradio.
     */
    private final BroadcastReceiver accReceiver = new BroadcastReceiver() {
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
            }
        }
    };

    /**
     * Traite l'événement de mise sous tension du contact (ACC ON) et notifie tous les abonnés.
     */
    private void handleAccOnEvent() {
        CarLog.i(TAG, "Receive: com.qf.action.ACC_ON");
        isAccOn = true;
        notifyAccChanged(true);
    }

    /**
     * Traite l'événement de coupure du contact (ACC OFF) et notifie tous les abonnés.
     */
    private void handleAccOffEvent() {
        CarLog.i(TAG, "Receive: com.qf.action.ACC_OFF");
        isAccOn = false;
        notifyAccChanged(false);
    }

    /**
     * Notifie tous les abonnés d'un changement d'état du contact du véhicule.
     *
     * @param accOn L'état du contact (true = allumé, false = coupé).
     */
    private synchronized void notifyAccChanged(boolean accOn) {
        for (IgnitionListener listener : listeners) {
            listener.onAccStateChanged(accOn);
        }
    }

    /**
     * Classe interne fournissant l'instance du service aux composants clients lors du binding.
     */
    public class LocalBinder extends Binder {
        /**
         * Retourne l'instance courante de l'IgnitionService.
         *
         * @return Le service d'état du contact actif.
         */
        public IgnitionService getService() {
            return IgnitionService.this;
        }
    }

    /**
     * Ajoute un nouvel abonné à la liste de diffusion des changements d'état du contact.
     * Transmet immédiatement à ce nouvel abonné l'état actuel.
     *
     * @param listener L'écouteur à ajouter.
     */
    public synchronized void addListener(IgnitionListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            listener.onAccStateChanged(isAccOn);
        }
    }

    /**
     * Retire un abonné de la liste de diffusion.
     *
     * @param listener L'écouteur à retirer.
     */
    public synchronized void removeListener(IgnitionListener listener) {
        listeners.remove(listener);
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
     * Initialise le service et enregistre le récepteur pour l'état du contact (ACC).
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.qf.action.ACC_ON");
        filter.addAction("com.qf.action.ACC_OFF");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(accReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(accReceiver, filter);
        }
    }

    /**
     * Désenregistre le récepteur à la destruction du service.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(accReceiver);
    }
}
