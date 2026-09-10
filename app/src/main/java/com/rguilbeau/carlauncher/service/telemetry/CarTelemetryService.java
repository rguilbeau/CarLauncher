package com.rguilbeau.carlauncher.service.telemetry;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Service centralisant la communication avec le service CAN bus de l'autoradio (package
 * {@code com.qf.vehicle}). Décode et distribue en temps réel la télémétrie du véhicule (vitesse,
 * régime moteur, kilométrage total) aux composants abonnés via le patron de conception Observateur.
 * <p>
 * Ce service ne gère que le client AIDL {@code ICanBusServiceFeature} exposé par l'autoradio.
 * L'état du contact (ACC_ON/ACC_OFF) est géré séparément par
 * {@link com.rguilbeau.carlauncher.service.ignition.IgnitionService}.
 * <p>
 * Contrairement au broadcast {@code com.qf.vehicle.action.DATA_SHARE} (limité à une mise à jour
 * toutes les 2 secondes côté autoradio), ce canal AIDL délivre chaque trame CAN décodée sans
 * throttle, donc en temps réel.
 */
public class CarTelemetryService extends Service {

    /**
     * Tag utilisé pour l'identification des messages de journalisation de ce service.
     */
    private static final String TAG = "CarTelemetryService";

    /**
     * Interface de communication permettant aux composants liés d'interagir avec ce service.
     */
    private final IBinder binder = new LocalBinder();

    /**
     * Liste des écouteurs abonnés aux événements de télémétrie du véhicule.
     */
    private final List<CarTelemetryListener> listeners = new ArrayList<>();

    /**
     * Dernière vitesse calculée du véhicule en km/h.
     */
    private int currentSpeed = 0;

    /**
     * Dernier régime moteur (RPM) calculé du véhicule en tours par minute.
     */
    private int currentRpm = 0;

    /**
     * Dernier kilométrage total connu du véhicule (odomètre), en kilomètres.
     * Vaut -1 tant qu'aucune valeur valide n'a été reçue du bus CAN.
     * <p>
     * Stocké en {@code double} (et non {@code float}) : au-delà de quelques dizaines de milliers
     * de km, un {@code float} (32 bits, ~7 chiffres significatifs) n'a plus assez de précision pour
     * distinguer des écarts de 0,1 km, ce qui fausse les calculs de distance basés dessus. Un
     * {@code double} (~15-17 chiffres significatifs) offre une marge très largement suffisante pour
     * n'importe quel kilométrage réaliste.
     */
    private double currentMileageKm = -1d;

    // ---------------------------------------------------------------------------------------
    // Client AIDL vers le service CAN bus de l'autoradio (com.qf.vehicle.service.VehicleService)
    // ---------------------------------------------------------------------------------------

    /**
     * Action du service CAN bus exposé par l'application système de l'autoradio.
     */
    private static final String CANBUS_ACTION = "com.qf.vehicle.service.ACTION_CAN_SERVICE";

    /**
     * Package de l'application système de l'autoradio hébergeant le service CAN bus.
     */
    private static final String CANBUS_PACKAGE = "com.qf.vehicle";

    /**
     * Descripteur d'interface AIDL de {@code ICanBusServiceFeature}, utilisé pour le jeton d'interface
     * des transactions Binder. Doit correspondre exactement au nom qualifié utilisé côté autoradio.
     */
    private static final String FEATURE_DESCRIPTOR = "com.qf.vehicle.service.ICanBusServiceFeature";

    /**
     * Descripteur d'interface AIDL de {@code ICanBusServiceCallback}, utilisé pour le jeton d'interface
     * des transactions Binder reçues par notre callback.
     */
    private static final String CALLBACK_DESCRIPTOR = "com.qf.vehicle.service.ICanBusServiceCallback";

    /**
     * Code de transaction Binder de {@code ICanBusServiceFeature#initCanbusSdkConfig}.
     */
    private static final int TX_INIT_SDK_CONFIG = 1;

    /**
     * Code de transaction Binder de {@code ICanBusServiceFeature#addCarbodyStateCallBack}.
     */
    private static final int TX_ADD_CARBODY_STATE_CALLBACK = 11;

    /**
     * Code de transaction Binder de {@code ICanBusServiceCallback#onGetPackedData}.
     */
    private static final int TX_ON_GET_PACKED_DATA = 2;

    /**
     * Nom d'application attendu par le SDK CAN bus pour valider l'initialisation (mode "App").
     * Valeur codée en dur côté autoradio (classe AidlFeature4App) : seul ce couple nom/clé active
     * la distribution des trames CarbodyState pour une application tierce.
     */
    private static final String SDK_APP_NAME = "QFApp";

    /**
     * Clé d'activation attendue par le SDK CAN bus, associée à {@link #SDK_APP_NAME}.
     */
    private static final String SDK_APP_KEY = "832ded976b28e7ee81a688a4f4095331";

    /**
     * Type de trame CarbodyState dans le protocole "packé" du SDK CAN bus (en-tête 0x98).
     */
    private static final byte FRAME_TYPE_CARBODY_STATE = 2;

    /**
     * Octet d'en-tête identifiant une trame "packée" du SDK CAN bus.
     */
    private static final byte FRAME_HEADER = (byte) 0x98;

    /**
     * Référence brute vers le binder distant du service CAN bus, une fois la connexion établie.
     */
    private IBinder canBusBinder;

    /**
     * Notre callback, exposé au service CAN bus distant pour recevoir chaque trame CarbodyState.
     * Implémenté manuellement au niveau Binder (sans passer par un fichier .aidl généré) afin de ne
     * reproduire que les 3 méthodes réellement utilisées par {@code ICanBusServiceCallback}, en
     * respectant les codes de transaction exacts de l'interface d'origine.
     */
    private final Binder carbodyStateCallback = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(CALLBACK_DESCRIPTOR);
                return true;
            }
            if (code == TX_ON_GET_PACKED_DATA) {
                data.enforceInterface(CALLBACK_DESCRIPTOR);
                byte[] payload = data.createByteArray();
                if (reply != null) reply.writeNoException();
                handleCanBusPayload(payload);
                return true;
            }
            // Codes 1 (onGetSrcData) et 3 (onGetLeapMotorSettings) : non utilisés par notre
            // abonnement (addCarbodyStateCallBack), mais acquittés pour ne jamais faire échouer
            // une transaction bloquante côté autoradio.
            if (code == 1 || code == 3) {
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    /**
     * Gère la connexion/déconnexion au service CAN bus distant. Android relie et délie
     * automatiquement (BIND_AUTO_CREATE) ce service en fonction de la disponibilité du processus
     * {@code com.qf.vehicle} ; onServiceConnected est donc rappelée automatiquement après un
     * redémarrage du service côté autoradio.
     */
    private final ServiceConnection canBusConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            canBusBinder = service;
            try {
                initCanbusSdkConfig();
                registerCarbodyStateCallback();
                CarLog.i(TAG, "Connected to CAN bus AIDL service (com.qf.vehicle)");
            } catch (RemoteException e) {
                CarLog.e(TAG, "Error initializing CAN bus AIDL service", e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            CarLog.w(TAG, "CAN bus AIDL service disconnected");
            canBusBinder = null;
        }
    };

    /**
     * Lie ce service au service CAN bus exposé par l'autoradio (package {@code com.qf.vehicle}).
     * Le service étant exporté sans permission particulière, aucune autorisation spéciale n'est requise.
     */
    private void bindCanBus() {
        try {
            Intent intent = new Intent(CANBUS_ACTION);
            intent.setPackage(CANBUS_PACKAGE);
            boolean bound = bindService(intent, canBusConnection, Context.BIND_AUTO_CREATE);
            if (!bound) {
                CarLog.e(TAG, "Unable to bind CAN bus AIDL service, is com.qf.vehicle installed?");
            }
        } catch (Exception e) {
            CarLog.e(TAG, "Error binding CAN bus AIDL service", e);
        }
    }

    /**
     * Envoie la trame d'initialisation du SDK CAN bus ("mode App") via une transaction Binder brute.
     * Requis pour que l'autoradio commence à distribuer les trames CarbodyState à cette application.
     *
     * @throws RemoteException Si la transaction Binder échoue.
     */
    private void initCanbusSdkConfig() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(FEATURE_DESCRIPTOR);
            data.writeByte((byte) 0); // demandType = App
            data.writeString(SDK_APP_NAME);
            data.writeString(SDK_APP_KEY);
            canBusBinder.transact(TX_INIT_SDK_CONFIG, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Enregistre notre callback auprès du service CAN bus distant pour recevoir chaque trame
     * CarbodyState décodée (vitesse, régime moteur, kilométrage), sans throttle.
     *
     * @throws RemoteException Si la transaction Binder échoue.
     */
    private void registerCarbodyStateCallback() throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(FEATURE_DESCRIPTOR);
            data.writeStrongBinder(carbodyStateCallback);
            canBusBinder.transact(TX_ADD_CARBODY_STATE_CALLBACK, data, reply, 0);
            reply.readException();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Décode une trame "packée" reçue du service CAN bus et met à jour la vitesse, le régime moteur
     * et le kilométrage total lorsqu'il s'agit d'une trame CarbodyState (type 2).
     * <p>
     * Format (protocole {@code QfSdkDataRule} de l'autoradio) : octet 0 = en-tête (0x98),
     * octet 1 = type de trame, octet 2 = longueur, octets 3+ = charge utile.
     *
     * @param payload Le tableau d'octets brut transmis par le callback {@code onGetPackedData}.
     */
    private void handleCanBusPayload(byte[] payload) {
        if (payload == null || payload.length < 19) return;
        if (payload[0] != FRAME_HEADER) return;
        if (payload[1] != FRAME_TYPE_CARBODY_STATE) return;

        // Ce callback est invoqué sur un thread du pool Binder (pas le thread principal) : les
        // mises à jour d'état sont donc synchronisées, en cohérence avec les accesseurs
        // synchronized (getCurrentSpeed, etc.).
        int speedToNotify;
        int rpmToNotify;
        double mileageToNotify = Double.NaN;

        int newSpeed = readBigEndianUnsigned(payload, 8, 2);
        int newRpm = readBigEndianUnsigned(payload, 10, 2);
        int rawMileage = readBigEndianUnsigned(payload, 16, 3);

        synchronized (this) {
            // 0xFFFF / 0xFFFFFF = valeur "non disponible" signalée par le bus CAN : on garde la
            // dernière valeur connue plutôt que d'écraser avec une valeur aberrante.
            if (newSpeed != 0xFFFF && newSpeed < 10000) currentSpeed = newSpeed;
            if (newRpm != 0xFFFF && newRpm < 10000) currentRpm = newRpm;
            speedToNotify = currentSpeed;
            rpmToNotify = currentRpm;

            if (rawMileage != 0xFFFFFF) {
                currentMileageKm = rawMileage / 10.0d;
                mileageToNotify = currentMileageKm;
            }
        }

        notifyTelemetry(speedToNotify, rpmToNotify);
        if (!Double.isNaN(mileageToNotify)) {
            notifyMileageUpdated(mileageToNotify);
        }

        logDrivingMileageDiagnostic(payload);
    }

    /**
     * Log de diagnostic temporaire (Peugeot 407) : {@code mDrivingMileageTotal} n'est jamais rempli
     * par le parseur véhicule pour ce modèle, mais {@code mDrivingMileage1}/{@code mDrivingMileage2}
     * (offsets 21 et 23 de la trame CarbodyState, cf. {@code QfSdkDataParse.parseCarbodyState2Sdk})
     * le sont peut-être. Ce log sert uniquement à vérifier, sur un trajet réel, si ces valeurs
     * changent en continu ou seulement lorsque la page correspondante de l'ordinateur de bord est
     * sélectionnée sur le combiné. Ne modifie aucun état, ne notifie aucun abonné.
     *
     * @param payload La trame CarbodyState complète (même tableau que {@link #handleCanBusPayload}).
     */
    private void logDrivingMileageDiagnostic(byte[] payload) {
        if (payload.length < 23) return;
        int rawTrip1 = readBigEndianUnsigned(payload, 21, 2);
        String trip1 = rawTrip1 == 0xFFFF ? "n/a" : (rawTrip1 / 10.0d) + "km";

        String trip2 = "n/a (trame trop courte)";
        if (payload.length >= 25) {
            int rawTrip2 = readBigEndianUnsigned(payload, 23, 2);
            trip2 = rawTrip2 == 0xFFFF ? "n/a" : (rawTrip2 / 10.0d) + "km";
        }

        CarLog.d(TAG, "[DIAG] mDrivingMileage1(raw offset21)=" + trip1 + " mDrivingMileage2(raw offset23)=" + trip2);
    }

    /**
     * Lit un entier non signé en gros-boutiste (big-endian) dans un tableau d'octets.
     *
     * @param data   Le tableau source.
     * @param offset L'index de départ.
     * @param length Le nombre d'octets à lire (1 à 4).
     * @return La valeur entière reconstituée.
     */
    private static int readBigEndianUnsigned(byte[] data, int offset, int length) {
        int value = 0;
        for (int i = 0; i < length; i++) {
            value = (value << 8) | (data[offset + i] & 0xFF);
        }
        return value;
    }

    // ---------------------------------------------------------------------------------------

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
     * Ajoute un nouvel abonné à la liste de diffusion des événements de télémétrie.
     * Transmet immédiatement à ce nouvel abonné les dernières valeurs connues.
     *
     * @param listener L'écouteur à ajouter.
     */
    public synchronized void addListener(CarTelemetryListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
            listener.onSpeedUpdated(currentSpeed);
            listener.onRpmUpdated(currentRpm);
            if (currentMileageKm >= 0d) {
                listener.onMileageUpdated(currentMileageKm);
            }
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
     * Notifie tous les abonnés d'une mise à jour des données de vitesse et de régime moteur.
     * Les deux valeurs proviennent de la même trame CAN, elles sont donc notifiées ensemble dans
     * une seule itération de la liste d'abonnés (chacun n'implémentant que ce dont il a besoin).
     *
     * @param speed La vitesse en km/h.
     * @param rpm   Le régime en tr/min.
     */
    private synchronized void notifyTelemetry(int speed, int rpm) {
        for (CarTelemetryListener listener : listeners) {
            listener.onSpeedUpdated(speed);
            listener.onRpmUpdated(rpm);
        }
    }

    /**
     * Notifie tous les abonnés d'une mise à jour du kilométrage total du véhicule.
     *
     * @param totalKm Le kilométrage total en kilomètres.
     */
    private synchronized void notifyMileageUpdated(double totalKm) {
        for (CarTelemetryListener listener : listeners) {
            listener.onMileageUpdated(totalKm);
        }
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
     * Récupère de manière sécurisée le dernier kilométrage total connu du véhicule.
     *
     * @return Le kilométrage total en kilomètres, ou -1 si aucune valeur n'a encore été reçue.
     */
    public synchronized double getCurrentMileageKm() {
        return currentMileageKm;
    }

    /**
     * Initialise le service et lie le client AIDL du bus CAN pour la vitesse, le régime moteur
     * et le kilométrage.
     */
    @Override
    public void onCreate() {
        super.onCreate();
        bindCanBus();
    }

    /**
     * Nettoie la connexion au bus CAN à la destruction du service.
     */
    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unbindService(canBusConnection);
        } catch (IllegalArgumentException ignored) {
            // Service déjà délié (ex: com.qf.vehicle jamais connecté avec succès).
        }
    }
}
