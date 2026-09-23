package com.rguilbeau.carlauncher.service.telemetry.canbus.reader.canable;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.rguilbeau.carlauncher.service.telemetry.canbus.data.VehicleData;
import com.rguilbeau.carlauncher.service.telemetry.canbus.frame.Frame;
import com.rguilbeau.carlauncher.service.telemetry.canbus.reader.CanReader;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Lecteur CAN s'appuyant sur un adaptateur CANable (USB, protocole SLCAN) et la librairie
 * usb-serial-for-android. Se connecte au bus Confort du véhicule en écoute seule (Listen-Only)
 * à 125 kbps.
 * <p>
 * Cycle de vie : {@link #start} déclenche de façon asynchrone la détection de l'adaptateur, la
 * demande de permission USB si nécessaire, puis l'ouverture du port et la lecture continue.
 * Aucune de ces étapes ne bloque le thread appelant. {@link #stop} est sûre à appeler à tout
 * moment (avant connexion, après un échec d'initialisation, ou plusieurs fois de suite).
 * </p>
 * <p>
 * Reconnexion : l'adaptateur peut être absent au démarrage, ou débranché/rebranché en cours de
 * trajet. Le lecteur écoute les évènements USB système (branchement/débranchement) pour
 * retenter une connexion dès que l'adaptateur réapparaît, et retente aussi automatiquement après
 * une erreur de lecture inattendue (glitch transitoire sans débranchement physique). Voir
 * {@link #handleDisconnect}.
 * </p>
 */
public class CanableReader implements CanReader {

    /** Tag utilisé pour l'identification des messages de journalisation de cette classe. */
    private static final String TAG = "CanableReader";

    /** Action du broadcast interne utilisé pour récupérer le résultat de la demande de permission USB. */
    private static final String ACTION_USB_PERMISSION = "com.rguilbeau.carlauncher.USB_PERMISSION";

    /** Vitesse du port série entre le boîtier et l'adaptateur CANable (bauds). */
    private static final int SERIAL_BAUD_RATE = 115200;

    /** Délai d'écriture, en millisecondes, pour les commandes d'initialisation SLCAN. */
    private static final int WRITE_TIMEOUT_MS = 1000;

    /** Délai avant une nouvelle tentative de connexion après une erreur de lecture inattendue. */
    private static final long RECONNECT_DELAY_MS = 3000;

    private final Context context;

    /**
     * Exécuteur mono-thread portant : la détection/connexion de l'adaptateur, la boucle de
     * lecture de {@link SerialInputOutputManager} une fois connecté, et les tentatives de
     * reconnexion différées ({@link #handleDisconnect}).
     */
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    /** Accumulateur des octets reçus, entre deux fins de trame SLCAN ("\r"). */
    private final StringBuilder readBuffer = new StringBuilder();

    /**
     * Verrou dédié à {@link #port}/{@link #ioManager}/{@link #connectedDevice} : ces champs sont
     * écrits depuis le thread de {@link #executor} ({@link #openAndInit}, {@link #handleDisconnect})
     * et lus/écrits depuis le thread appelant de {@link #stop} (le thread principal, via
     * {@code TelemetryService}) ainsi que depuis le thread système qui délivre les broadcasts USB
     * — sans lui, rien ne garantit la visibilité entre threads ni n'empêche une fermeture
     * concurrente à l'ouverture.
     */
    private final Object portLock = new Object();

    /** Données du véhicule à mettre à jour, reçues via {@link #start}. */
    private VehicleData data;

    /** Décodeurs disponibles, indexés par id de trame CAN, reçus via {@link #start}. */
    private Map<String, Frame> frames;

    /** Port série ouvert vers l'adaptateur, ou {@code null} si non connecté. Protégé par {@link #portLock}. */
    private UsbSerialPort port;

    /** Boucle de lecture continue de {@link #port}, ou {@code null} si non connecté. Protégé par {@link #portLock}. */
    private SerialInputOutputManager ioManager;

    /** Adaptateur actuellement connecté, pour reconnaître son débranchement (voir le receiver). */
    private UsbDevice connectedDevice;

    /** Évite un double enregistrement du receiver si {@link #start} est appelée plusieurs fois. */
    private boolean receiverRegistered;

    /**
     * {@code true} entre {@link #start} et {@link #stop} : conditionne les tentatives de
     * reconnexion automatique (aucune reconnexion ne doit être planifiée après l'arrêt du lecteur).
     */
    private volatile boolean running;

    /**
     * @param context Contexte utilisé pour accéder au service USB et enregistrer le
     *                {@link #usbEventsReceiver}. L'{@link Context#getApplicationContext()} est
     *                conservé plutôt que {@code context} lui-même, ce lecteur pouvant vivre plus
     *                longtemps que l'appelant.
     */
    public CanableReader(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Reçoit :
     * <ul>
     *     <li>le résultat de la demande de permission USB déclenchée dans {@link #connect} ;</li>
     *     <li>le branchement d'un périphérique USB, pour retenter une connexion ;</li>
     *     <li>le débranchement d'un périphérique USB, pour détecter la perte de l'adaptateur.</li>
     * </ul>
     */
    private final BroadcastReceiver usbEventsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;

            switch (action) {
                case ACTION_USB_PERMISSION: {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

                    if (granted && device != null) {
                        executor.execute(() -> openAndInit(device));
                    } else {
                        CarLog.e(TAG, "Permission USB refusée pour l'adaptateur CANable");
                    }
                    break;
                }

                case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                    CarLog.d(TAG, "Périphérique USB branché, tentative de connexion au CANable");
                    executor.execute(CanableReader.this::connect);
                    break;

                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    UsbDevice detached = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    boolean isOurDevice;
                    synchronized (portLock) {
                        isOurDevice = connectedDevice != null && connectedDevice.equals(detached);
                    }
                    if (isOurDevice) {
                        CarLog.d(TAG, "Adaptateur CANable débranché");
                        // Pas de reconnexion planifiée : on attend le prochain ACTION_USB_DEVICE_ATTACHED.
                        executor.execute(() -> handleDisconnect(false));
                    }
                    break;
                }

                default:
                    break;
            }
        }
    };

    /**
     * Enregistre le récepteur d'évènements USB et lance la première tentative de connexion de
     * façon asynchrone. Sans effet si déjà démarré (voir {@link #receiverRegistered}).
     */
    @Override
    public void start(VehicleData data, Map<String, Frame> frames) {
        if (receiverRegistered) {
            CarLog.e(TAG, "start() appelé alors que le lecteur est déjà démarré, ignoré");
            return;
        }

        this.data = data;
        this.frames = frames;
        running = true;

        registerUsbEventsReceiver();
        executor.execute(this::connect);
    }

    /** Enregistre {@link #usbEventsReceiver} pour les 3 actions qu'il traite. */
    private void registerUsbEventsReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbEventsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(usbEventsReceiver, filter);
        }
        receiverRegistered = true;
    }

    /**
     * Détecte l'adaptateur CANable branché, puis demande la permission USB si besoin (déclenche
     * une boîte de dialogue système la première fois) avant de poursuivre vers
     * {@link #openAndInit}. Sans effet si déjà connecté. Si aucun adaptateur n'est détecté, ne
     * plie pas : la connexion sera retentée au prochain {@code ACTION_USB_DEVICE_ATTACHED}.
     * Exécutée sur {@link #executor} : encadrée d'un try/catch global pour qu'aucune exception ne
     * puisse tuer silencieusement le thread de connexion (ou l'app, un thread en arrière-plan
     * pouvant être couvert par le handler de crash par défaut).
     */
    private void connect() {
        if (!running) return;

        synchronized (portLock) {
            if (port != null) return;
        }

        try {
            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            if (manager == null) {
                CarLog.e(TAG, "UsbManager indisponible sur cet appareil");
                return;
            }

            List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager);
            if (drivers.isEmpty()) {
                CarLog.d(TAG, "Aucun adaptateur CANable détecté, en attente de branchement");
                return;
            }

            UsbDevice device = drivers.get(0).getDevice();

            if (manager.hasPermission(device)) {
                openAndInit(device);
                return;
            }

            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    context, 0, new Intent(ACTION_USB_PERMISSION), flags);
            manager.requestPermission(device, permissionIntent);
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur lors de la détection de l'adaptateur CANable", e);
        }
    }

    /**
     * Ouvre le port série de l'adaptateur, le configure et lance la séquence d'initialisation
     * SLCAN, puis démarre la lecture continue. Exécutée sur {@link #executor}.
     *
     * @param device Adaptateur USB dont la permission vient d'être confirmée.
     */
    private void openAndInit(UsbDevice device) {
        synchronized (portLock) {
            if (port != null) {
                CarLog.e(TAG, "openAndInit() appelé alors qu'un port est déjà ouvert, ignoré");
                return;
            }

            UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            UsbSerialDriver driver = findDriver(manager, device);

            if (driver == null || driver.getPorts().isEmpty()) {
                CarLog.e(TAG, "Adaptateur CANable introuvable à l'ouverture");
                return;
            }

            try {
                UsbDeviceConnection connection = manager.openDevice(device);
                if (connection == null) {
                    CarLog.e(TAG, "Impossible d'ouvrir la connexion USB vers l'adaptateur CANable");
                    return;
                }

                port = driver.getPorts().get(0);
                port.open(connection);
                port.setParameters(SERIAL_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);

                // Séquence d'initialisation SLCAN : fermeture du canal, 125 kbps (bus Confort), écoute seule
                port.write("C\r".getBytes(StandardCharsets.US_ASCII), WRITE_TIMEOUT_MS);
                Thread.sleep(50);
                port.write("S3\r".getBytes(StandardCharsets.US_ASCII), WRITE_TIMEOUT_MS);
                Thread.sleep(50);
                port.write("L\r".getBytes(StandardCharsets.US_ASCII), WRITE_TIMEOUT_MS);
                CarLog.d(TAG, "CANable initialisé sur le bus Confort (125kbps) en Listen-Only");

                ioManager = new SerialInputOutputManager(port, new SerialInputOutputManager.Listener() {
                    @Override
                    public void onNewData(byte[] newData) {
                        handleNewData(newData);
                    }

                    @Override
                    public void onRunError(Exception e) {
                        CarLog.e(TAG, "Erreur de lecture du port USB CANable, tentative de reconnexion", e);
                        // Peut être un débranchement (auquel cas ACTION_USB_DEVICE_DETACHED suivra et
                        // handleDisconnect(false) prendra le relais sans effet ici) ou un glitch
                        // transitoire avec l'adaptateur toujours branché : on retente dans les deux cas.
                        executor.execute(() -> handleDisconnect(true));
                    }
                });
                executor.execute(ioManager);
                connectedDevice = device;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CarLog.e(TAG, "Initialisation de l'adaptateur CANable interrompue", e);
                closePortLocked();
            } catch (Exception e) {
                CarLog.e(TAG, "Erreur d'initialisation de l'adaptateur CANable", e);
                closePortLocked();
            }
        }
    }

    /**
     * Retrouve, parmi les pilotes actuellement détectés, celui correspondant à {@code device}
     * (le pilote obtenu lors de la détection initiale n'est pas conservé tel quel, la permission
     * ayant pu être accordée de façon asynchrone entre-temps).
     */
    private UsbSerialDriver findDriver(UsbManager manager, UsbDevice device) {
        for (UsbSerialDriver driver : UsbSerialProber.getDefaultProber().findAllDrivers(manager)) {
            if (driver.getDevice().equals(device)) {
                return driver;
            }
        }
        return null;
    }

    /**
     * Referme le port/l'{@link SerialInputOutputManager} en cours et, si demandé, planifie une
     * nouvelle tentative de connexion après {@link #RECONNECT_DELAY_MS}. Idempotente : peut être
     * appelée alors qu'aucune connexion n'est active (ex: appels redondants entre
     * {@code onRunError} et {@code ACTION_USB_DEVICE_DETACHED} pour le même évènement).
     *
     * @param scheduleRetry {@code true} pour retenter automatiquement la connexion (cas d'une
     *                      erreur de lecture, où l'adaptateur est peut-être toujours branché) ;
     *                      {@code false} lorsqu'un débranchement explicite a été détecté, auquel
     *                      cas on attend le prochain branchement plutôt que de boucler à vide.
     */
    private void handleDisconnect(boolean scheduleRetry) {
        synchronized (portLock) {
            if (ioManager != null) {
                ioManager.stop();
                ioManager = null;
            }
            connectedDevice = null;
            closePortLocked();
        }

        if (scheduleRetry && running) {
            CarLog.d(TAG, "Nouvelle tentative de connexion à l'adaptateur CANable dans " + RECONNECT_DELAY_MS + " ms");
            try {
                executor.schedule(this::connect, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // Le lecteur a été arrêté entre-temps (executor fermé) : rien à faire.
            }
        }
    }

    /**
     * Accumule les octets reçus du port série et découpe le flux en lignes SLCAN complètes
     * ("\r"-terminées) à faire décoder par {@link #parseCanFrame}.
     */
    private void handleNewData(byte[] newData) {
        if (newData == null || newData.length == 0) return;

        String text = new String(newData, StandardCharsets.US_ASCII);

        synchronized (readBuffer) {
            readBuffer.append(text);

            int index = readBuffer.indexOf("\r");
            while (index != -1) {
                String frame = readBuffer.substring(0, index);
                readBuffer.delete(0, index + 1);

                parseCanFrame(frame);

                index = readBuffer.indexOf("\r");
            }
        }
    }

    /**
     * Décode une ligne SLCAN reçue (trame standard "t..." ou étendue "T...") et route l'id +
     * les données vers le {@link Frame} correspondant via {@link #dispatchFrame}. Les autres
     * types de lignes (accusés de réception, erreurs bus...) sont ignorés. Toute ligne dont le
     * format ne correspond pas à celui attendu (longueur, DLC invalide) est ignorée
     * silencieusement plutôt que de lever une exception, le bus pouvant occasionnellement
     * livrer des trames tronquées.
     */
    private void parseCanFrame(String frameHex) {
        if (frameHex.isEmpty()) return;

        int idLength;
        switch (frameHex.charAt(0)) {
            case 't':
                idLength = 3;
                break;
            case 'T':
                idLength = 8;
                break;
            default:
                return;
        }

        if (frameHex.length() < idLength + 2) return;

        String id = frameHex.substring(1, 1 + idLength).toUpperCase(Locale.ROOT);
        int dlc = Character.digit(frameHex.charAt(1 + idLength), 16);
        if (dlc < 0 || dlc > 8) return;

        int dataStart = idLength + 2;
        int dataEnd = dataStart + dlc * 2;
        if (frameHex.length() < dataEnd) return;

        dispatchFrame(id, frameHex.substring(dataStart, dataEnd));
    }

    /**
     * Route une trame décodée vers le {@link Frame} en charge de son id, s'il en existe un pour
     * le véhicule ciblé. Le décodage ({@link Frame#parse}) est encadré d'un garde-fou : une
     * trame malformée ou un bug de décodage ne doit jamais interrompre la lecture des trames
     * suivantes.
     */
    private void dispatchFrame(String id, String dataHex) {
        Frame frame = frames.get(id);
        if (frame == null) return;

        try {
            frame.parse(dataHex, data);
        } catch (Exception e) {
            CarLog.e(TAG, "Erreur de décodage de la trame CAN " + id, e);
        }
    }

    /**
     * Arrête définitivement le lecteur : désenregistre le récepteur d'évènements USB, annule
     * toute reconnexion planifiée, ferme la connexion en cours s'il y en a une et libère le
     * thread de lecture. Sûre à appeler à tout moment (voir {@link CanReader#stop}).
     */
    @Override
    public void stop() {
        running = false;

        if (receiverRegistered) {
            try {
                context.unregisterReceiver(usbEventsReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver déjà désenregistré : sans effet, on l'ignore.
            }
            receiverRegistered = false;
        }

        synchronized (portLock) {
            if (ioManager != null) {
                ioManager.stop();
                ioManager = null;
            }
            connectedDevice = null;
            closePortLocked();
        }

        // Annule toute reconnexion planifiée et libère le thread de lecture.
        executor.shutdownNow();
    }

    /**
     * Ferme le port série s'il est ouvert, en journalisant sans propager une éventuelle erreur.
     * Doit être appelée avec {@link #portLock} tenu.
     */
    private void closePortLocked() {
        if (port == null) return;

        try {
            port.close();
        } catch (IOException e) {
            CarLog.e(TAG, "Erreur à la fermeture du port USB CANable", e);
        } finally {
            port = null;
        }
    }
}
