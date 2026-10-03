package com.rguilbeau.carlauncher.service.telemetry;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

import com.rguilbeau.carlauncher.service.telemetry.canbus.CanBus;
import com.rguilbeau.carlauncher.service.telemetry.canbus.data.Property;
import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Vehicle;
import com.rguilbeau.carlauncher.service.telemetry.canbus.reader.CanReader;
import com.rguilbeau.carlauncher.service.telemetry.canbus.reader.canable.CanableReader;
import com.rguilbeau.carlauncher.service.telemetry.canbus.reader.simulator.ReaderSimulatorPeugeot407;
import com.rguilbeau.carlauncher.utils.DeviceEnvironment;
import com.rguilbeau.carlauncher.utils.log.CarLog;

/**
 * Service d'arrière-plan exposant les données du bus CAN du véhicule (vitesse, RPM, contact,
 * kilométrage) sous forme de {@link Property} observables, via {@link #getData()}.
 * <p>
 * Utilisation : lier ce service ({@code bindService}), puis par exemple
 * {@code getData().rpm.bind(this::onRpmChanged)} (voir {@link Property} pour le thread
 * d'exécution des observateurs et les précautions de désabonnement).
 * </p>
 */
public class TelemetryService extends Service {

    /** Tag utilisé pour l'identification des messages de journalisation de cette classe. */
    private static final String TAG = "TelemetryService";

    /** Interface de communication remise aux composants clients lors du binding. */
    private final IBinder binder = new LocalBinder();

    /**
     * Données de repli retournées par {@link #getData()} si {@link #canBus} n'a pas pu être
     * initialisé (ex: erreur au démarrage) : évite un {@code NullPointerException} côté
     * appelants, au prix de valeurs qui ne seront jamais mises à jour.
     */
    private final VehicleData fallbackData = new VehicleData();

    /** Point d'entrée du sous-système CAN, {@code null} tant que {@link #onCreate} n'a pas réussi. */
    private CanBus canBus;

    /** Fournit l'instance du service aux composants clients lors du binding. */
    public class LocalBinder extends Binder {

        /**
         * @return L'instance courante de {@link TelemetryService}.
         */
        public TelemetryService getService() {
            return TelemetryService.this;
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
     * Initialise le service : résout les décodeurs de trames pour le véhicule ciblé et démarre
     * la lecture du bus CAN — sur adaptateur CANable réel en production
     * ({@link DeviceEnvironment#isProd()}), sur {@link ReaderSimulatorPeugeot407} sinon (device
     * de dev/test, cas par défaut).
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // onCreate() s'exécute sur le thread principal : une exception non interceptée ici
        // tuerait le processus entier. On l'isole pour que l'app reste utilisable même sans
        // télémétrie CAN (adaptateur absent, erreur matérielle...).
        try {
            CanReader reader = DeviceEnvironment.isProd()
                    ? new CanableReader(this)
                    : new ReaderSimulatorPeugeot407(this);

            canBus = new CanBus(reader, Vehicle.PEUGEOT_407);
            canBus.start();
            CarLog.d(TAG, "CanBus started (" + reader.getClass().getSimpleName() + ")");
        } catch (Exception e) {
            CarLog.e(TAG, "Échec du démarrage du CanBus", e);
        }
    }

    /**
     * @return Les données du véhicule à observer via leurs {@code Property} (ex:
     *         {@code getData().rpm.bind(...)}). Ne retourne jamais {@code null} : si le bus
     *         CAN n'a pas pu démarrer, retourne un {@link VehicleData} figé à ses valeurs par
     *         défaut.
     */
    public VehicleData getData() {
        return canBus != null ? canBus.getData() : fallbackData;
    }

    /**
     * Nettoie les ressources et arrête la lecture du bus CAN à la destruction du service.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        if (canBus != null) {
            canBus.stop();
            CarLog.d(TAG, "CanBus stopped");
        }
    }
}
