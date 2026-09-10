package com.qf.vehicle;

import android.annotation.SuppressLint;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Faux service CAN bus reproduisant le strict minimum du protocole AIDL
 * {@code ICanBusServiceFeature} / {@code ICanBusServiceCallback} utilisé par CarLauncher
 * (voir {@code com.rguilbeau.carlauncher.service.telemetry.CarTelemetryService}), afin de pouvoir
 * tester le chemin AIDL complet (bind, transactions Binder, callback) sur émulateur, sans la vraie
 * tête d'unité.
 * <p>
 * Contrairement à la vraie autoradio, ce stub n'envoie aucune trame automatiquement : il pousse
 * une trame CarbodyState (vitesse, régime moteur, kilométrage) uniquement sur demande, via un
 * broadcast adb dédié :
 * <pre>
 * adb shell am broadcast -a com.qf.vehicle.debug.SET_CARBODY_STATE \
 *     --ei speed 87 --ei rpm 2300 --ef mileage 123456.7
 * </pre>
 * Chaque extra est optionnel : toute valeur non précisée conserve sa dernière valeur connue (une
 * commande ne changeant que la vitesse ne réinitialise donc pas le RPM ou le kilométrage).
 * <p>
 * L'état courant est aussi diffusé via {@link #ACTION_STATE_CHANGED}, consommé par
 * {@link MainActivity} pour affichage (voir cette classe), uniquement à titre indicatif : ce
 * service ne dépend d'aucune activité pour fonctionner.
 * <p>
 * ATTENTION : ce module usurpe le package {@code com.qf.vehicle}. À n'installer que sur émulateur
 * ou appareil de test, jamais sur la vraie tablette.
 */
public class VehicleServiceStub extends Service {

    private static final String TAG = "VehicleServiceStub";

    /**
     * Action du broadcast de contrôle adb, propre à ce stub (n'existe pas côté vraie autoradio).
     */
    public static final String ACTION_SET_CARBODY_STATE = "com.qf.vehicle.debug.SET_CARBODY_STATE";
    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_RPM = "rpm";
    public static final String EXTRA_MILEAGE = "mileage";

    /**
     * Action locale (interne à ce process) informant {@link MainActivity} d'un changement d'état,
     * à titre purement indicatif pour l'affichage.
     */
    public static final String ACTION_STATE_CHANGED = "com.qf.vehicle.debug.STATE_CHANGED";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_INITIALIZED = "initialized";

    // Descripteurs et codes de transaction : identiques à ceux utilisés côté CarTelemetryService,
    // extraits du protocole AIDL décompilé de la vraie application com.qf.vehicle.
    private static final String FEATURE_DESCRIPTOR = "com.qf.vehicle.service.ICanBusServiceFeature";
    private static final String CALLBACK_DESCRIPTOR = "com.qf.vehicle.service.ICanBusServiceCallback";
    private static final int TX_INIT_SDK_CONFIG = 1;
    private static final int TX_ADD_CARBODY_STATE_CALLBACK = 11;
    private static final int TX_ON_GET_PACKED_DATA = 2;

    /**
     * Couple nom/clé attendu par le vrai SDK CAN bus pour valider l'initialisation (mode "App").
     * Reproduit ici pour vérifier que CarLauncher envoie bien les bonnes valeurs.
     */
    private static final String SDK_APP_NAME = "QFApp";
    private static final String SDK_APP_KEY = "832ded976b28e7ee81a688a4f4095331";

    private static final byte FRAME_HEADER = (byte) 0x98;
    private static final byte FRAME_TYPE_CARBODY_STATE = 2;

    /**
     * Référence vers l'instance unique du service en cours, pour permettre à {@link MainActivity}
     * de lire l'état courant sans passer par un bind (simple confort d'affichage).
     */
    private static volatile VehicleServiceStub instance;

    /**
     * Dernières valeurs poussées, réutilisées pour les extras absents d'une commande adb.
     */
    private int lastSpeed = 0;
    private int lastRpm = 800;
    private float lastMileageKm = 123456.7f;

    /**
     * true une fois {@code initCanbusSdkConfig} reçu avec le bon couple nom/clé, à l'image du
     * comportement réel (AidlFeature4App) qui ignore les trames tant que ce n'est pas le cas.
     */
    private boolean initialized = false;

    /**
     * Binder du callback CarLauncher enregistré via {@code addCarbodyStateCallBack}, ou null si
     * aucun client n'est (encore) connecté.
     */
    private IBinder carbodyCallback;

    private final IBinder.DeathRecipient callbackDeathRecipient = () -> {
        Log.w(TAG, "CarLauncher callback died");
        synchronized (this) {
            carbodyCallback = null;
        }
        broadcastState();
    };

    /**
     * Binder exposé via {@link #onBind}, implémentant manuellement les deux transactions
     * réellement utilisées par CarTelemetryService (pas de fichier .aidl généré).
     */
    private final IBinder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == IBinder.INTERFACE_TRANSACTION) {
                if (reply != null) reply.writeString(FEATURE_DESCRIPTOR);
                return true;
            }
            if (code == TX_INIT_SDK_CONFIG) {
                data.enforceInterface(FEATURE_DESCRIPTOR);
                byte demandType = data.readByte();
                String name = data.readString();
                String key = data.readString();
                synchronized (VehicleServiceStub.this) {
                    initialized = demandType == 0 && SDK_APP_NAME.equals(name) && SDK_APP_KEY.equals(key);
                }
                Log.i(TAG, "initCanbusSdkConfig name=" + name + " initialized=" + initialized);
                if (reply != null) reply.writeNoException();
                return true;
            }
            if (code == TX_ADD_CARBODY_STATE_CALLBACK) {
                data.enforceInterface(FEATURE_DESCRIPTOR);
                registerCallback(data.readStrongBinder());
                if (reply != null) reply.writeNoException();
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    /**
     * Récepteur du broadcast de contrôle adb : met à jour les valeurs précisées et pousse
     * immédiatement une nouvelle trame CarbodyState au client connecté.
     */
    private final BroadcastReceiver debugReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!ACTION_SET_CARBODY_STATE.equals(intent.getAction())) return;

            synchronized (VehicleServiceStub.this) {
                if (intent.hasExtra(EXTRA_SPEED)) lastSpeed = intent.getIntExtra(EXTRA_SPEED, lastSpeed);
                if (intent.hasExtra(EXTRA_RPM)) lastRpm = intent.getIntExtra(EXTRA_RPM, lastRpm);
                if (intent.hasExtra(EXTRA_MILEAGE)) lastMileageKm = intent.getFloatExtra(EXTRA_MILEAGE, lastMileageKm);
            }

            Log.i(TAG, "Push speed=" + lastSpeed + " rpm=" + lastRpm + " mileage=" + lastMileageKm);
            pushCarbodyState();
        }
    };

    private void registerCallback(IBinder callback) {
        synchronized (this) {
            if (carbodyCallback != null) {
                try {
                    carbodyCallback.unlinkToDeath(callbackDeathRecipient, 0);
                } catch (Exception ignored) {
                }
            }
            carbodyCallback = callback;
            if (carbodyCallback != null) {
                try {
                    carbodyCallback.linkToDeath(callbackDeathRecipient, 0);
                } catch (RemoteException e) {
                    Log.e(TAG, "linkToDeath failed", e);
                }
            }
        }
        Log.i(TAG, "CarbodyState callback registered: " + (callback != null));
        // Comme la vraie autoradio, on pousse l'état courant dès la connexion du client.
        pushCarbodyState();
    }

    /**
     * Construit une trame CarbodyState "packée" à partir des dernières valeurs connues et la
     * transmet au callback enregistré, exactement comme le ferait le service CAN bus réel.
     * Diffuse aussi l'état courant via {@link #ACTION_STATE_CHANGED} pour {@link MainActivity}.
     */
    private void pushCarbodyState() {
        broadcastState();

        IBinder callback;
        boolean ready;
        int speed;
        int rpm;
        float mileageKm;
        synchronized (this) {
            callback = carbodyCallback;
            ready = initialized;
            speed = lastSpeed;
            rpm = lastRpm;
            mileageKm = lastMileageKm;
        }

        if (callback == null) {
            Log.w(TAG, "No client connected yet, ignoring push");
            return;
        }
        if (!ready) {
            Log.w(TAG, "SDK not initialized yet, ignoring push");
            return;
        }

        byte[] payload = buildCarbodyStateFrame(speed, rpm, mileageKm);

        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CALLBACK_DESCRIPTOR);
            data.writeByteArray(payload);
            callback.transact(TX_ON_GET_PACKED_DATA, data, reply, 0);
            reply.readException();
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to push CarbodyState to callback", e);
            synchronized (this) {
                carbodyCallback = null;
            }
            broadcastState();
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * Diffuse l'état courant du stub (valeurs, connexion, initialisation) à titre indicatif pour
     * {@link MainActivity}. N'affecte en rien le fonctionnement du service lui-même.
     */
    private void broadcastState() {
        int speed;
        int rpm;
        float mileageKm;
        boolean connected;
        boolean ready;
        synchronized (this) {
            speed = lastSpeed;
            rpm = lastRpm;
            mileageKm = lastMileageKm;
            connected = carbodyCallback != null;
            ready = initialized;
        }

        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.putExtra(EXTRA_SPEED, speed);
        intent.putExtra(EXTRA_RPM, rpm);
        intent.putExtra(EXTRA_MILEAGE, mileageKm);
        intent.putExtra(EXTRA_CONNECTED, connected);
        intent.putExtra(EXTRA_INITIALIZED, ready);
        sendBroadcast(intent);
    }

    /**
     * Construit une trame "packée" CarbodyState minimale, avec les mêmes offsets que ceux décodés
     * par {@code CarTelemetryService.handleCanBusPayload} : en-tête 0x98, type 2, vitesse à
     * l'offset 8 (2 octets), RPM à l'offset 10 (2 octets), kilométrage total à l'offset 16
     * (3 octets, en dixièmes de km).
     *
     * @param speed     Vitesse en km/h.
     * @param rpm       Régime moteur en tr/min.
     * @param mileageKm Kilométrage total en kilomètres.
     * @return La trame encodée, prête à être transmise via {@code onGetPackedData}.
     */
    private static byte[] buildCarbodyStateFrame(int speed, int rpm, float mileageKm) {
        byte[] frame = new byte[19];
        frame[0] = FRAME_HEADER;
        frame[1] = FRAME_TYPE_CARBODY_STATE;
        frame[2] = 16; // longueur indicative, non exploitée par le décodeur de CarLauncher

        writeBigEndianUnsigned(frame, 8, 2, speed);
        writeBigEndianUnsigned(frame, 10, 2, rpm);
        writeBigEndianUnsigned(frame, 16, 3, Math.round(mileageKm * 10f));

        return frame;
    }

    /**
     * Écrit une valeur entière non signée en gros-boutiste (big-endian) dans un tableau d'octets,
     * symétrique de {@code CarTelemetryService.readBigEndianUnsigned}.
     */
    private static void writeBigEndianUnsigned(byte[] target, int offset, int length, int value) {
        for (int i = length - 1; i >= 0; i--) {
            target[offset + i] = (byte) (value & 0xFF);
            value >>= 8;
        }
    }

    /**
     * Retourne l'instance courante du service si elle existe, uniquement pour permettre à
     * {@link MainActivity} de lire l'état déjà connu à son ouverture.
     *
     * @return L'instance active du service, ou null s'il n'est pas encore démarré.
     */
    public static VehicleServiceStub getInstance() {
        return instance;
    }

    public synchronized int getLastSpeed() {
        return lastSpeed;
    }

    public synchronized int getLastRpm() {
        return lastRpm;
    }

    public synchronized float getLastMileageKm() {
        return lastMileageKm;
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    public synchronized boolean isClientConnected() {
        return carbodyCallback != null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "VehicleServiceStub created");

        IntentFilter filter = new IntentFilter(ACTION_SET_CARBODY_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(debugReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(debugReceiver, filter);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
        unregisterReceiver(debugReceiver);
    }
}
