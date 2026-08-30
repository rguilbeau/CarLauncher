package com.rguilbeau.carlauncher.utils.root;

import android.annotation.SuppressLint;
import android.hardware.input.InputManager;
import android.os.Looper;
import android.util.Log;
import android.view.InputEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;

public class RootDaemon {
    private static final String TAG = "CarLauncherRoot";

    // Variables déclarées volatile pour garantir la visibilité inter-threads sans blocage
    private static volatile Method setDisplayIdMethod = null;
    private static volatile Method injectMethod = null;
    private static volatile InputManager inputManager = null;

    public static void main(String[] args) {
        Log.i(TAG, "Démarrage du RootDaemon...");

        // 1. Initialisation asynchrone d'InputManager (ne bloque pas l'ouverture du serveur TCP)
        new Thread(() -> {
            if (Looper.myLooper() == null) {
                Looper.prepare();
            }
            initInputManager();
        }).start();

        // 2. Le serveur TCP ouvre son port immédiatement pour les commandes Shell
        try (ServerSocket serverSocket = new ServerSocket(9999)) {
            Log.i(TAG, "RootDaemon écoute sur le port 9999 (UID: 0)");

            while (true) {
                try {
                    Socket socket = serverSocket.accept();
                    Log.d(TAG, "Nouveau client TCP connecté : " + socket.getRemoteSocketAddress());
                    new Thread(() -> handleClient(socket)).start();
                } catch (Exception e) {
                    Log.e(TAG, "Erreur lors de l'acceptation d'une connexion socket", e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erreur fatale du serveur TCP sur le port 9999", e);
        }
    }

    @SuppressLint("BlockedPrivateApi")
    private static void initInputManager() {
        int attempts = 0;

        // Boucle infinie au boot jusqu'à ce que InputManagerService soit prêt
        while (inputManager == null) {
            try {
                attempts++;

                // Recherche de setDisplayId (dans InputEvent puis MotionEvent)
                Method setDisplayId;
                try {
                    setDisplayId = InputEvent.class.getDeclaredMethod("setDisplayId", int.class);
                } catch (NoSuchMethodException e) {
                    setDisplayId = MotionEvent.class.getDeclaredMethod("setDisplayId", int.class);
                }
                setDisplayId.setAccessible(true);

                // Récupération de l'instance d'InputManager
                Method getInstanceMethod = InputManager.class.getDeclaredMethod("getInstance");
                getInstanceMethod.setAccessible(true);
                InputManager im = (InputManager) getInstanceMethod.invoke(null);

                // Récupération de la méthode d'injection
                Method inject = InputManager.class.getDeclaredMethod("injectInputEvent", InputEvent.class, int.class);
                inject.setAccessible(true);

                if (im != null) {
                    setDisplayIdMethod = setDisplayId;
                    injectMethod = inject;
                    inputManager = im; // Assigné en dernier pour valider l'initialisation complète
                    Log.i(TAG, "InputManager connecté avec succès après " + attempts + " tentative(s) !");
                }
            } catch (Exception e) {
                Log.w(TAG, "InputManagerService indisponible au boot (tentative " + attempts + "), nouvelle vérification dans 1 s...");
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ignored) {}
            }
        }
    }

    private static void handleClient(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                if (trimmed.startsWith("TOUCH|")) {
                    handleTouch(trimmed.substring(6));
                } else {
                    Log.d(TAG, "Commande Shell reçue : " + trimmed);
                    handleShellCommand(trimmed);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Déconnexion du client TCP");
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    private static void handleShellCommand(String cmd) {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});

            // Fermeture immédiate des flux pour éviter tout blocage du sous-processus
            process.getInputStream().close();
            process.getOutputStream().close();
            process.getErrorStream().close();

            int exitCode = process.waitFor();
            Log.d(TAG, "Commande Shell exécutée. Code de sortie : " + exitCode);
        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de l'exécution de la commande Shell : " + cmd, e);
        }
    }

    private static void handleTouch(String payload) {
        // Sécurité : ignore les clics si appelés durant les premières secondes du boot avant l'accroche d'InputManager
        if (inputManager == null || setDisplayIdMethod == null || injectMethod == null) {
            Log.w(TAG, "Touch ignoré : InputManager est encore en cours d'initialisation au boot...");
            return;
        }

        try {
            // Format du payload : displayId|action|downTime|eventTime|count|x0,y0,id0;x1,y1,id1...
            String[] parts = payload.split("\\|");
            int displayId = Integer.parseInt(parts[0]);
            int action = Integer.parseInt(parts[1]);
            long downTime = Long.parseLong(parts[2]);
            long eventTime = Long.parseLong(parts[3]);
            int count = Integer.parseInt(parts[4]);

            String[] ptrs = parts[5].split(";");

            MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[count];
            MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];

            for (int i = 0; i < count; i++) {
                String[] c = ptrs[i].split(",");
                props[i] = new MotionEvent.PointerProperties();
                props[i].id = Integer.parseInt(c[2]);
                props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;

                coords[i] = new MotionEvent.PointerCoords();
                coords[i].x = Float.parseFloat(c[0]);
                coords[i].y = Float.parseFloat(c[1]);
                coords[i].pressure = 1.0f;
                coords[i].size = 1.0f;
            }

            MotionEvent event = MotionEvent.obtain(
                    downTime, eventTime, action, count,
                    props, coords, 0, 0, 1.0f, 1.0f, 0, 0, 4098, 0
            );

            setDisplayIdMethod.invoke(event, displayId);
            injectMethod.invoke(inputManager, event, 0); // 0 = INJECT_INPUT_EVENT_MODE_ASYNC

            event.recycle();

        } catch (Exception e) {
            Log.e(TAG, "Erreur lors de l'injection du tactile (payload : " + payload + ")", e);
        }
    }
}