package com.rguilbeau.carlauncher.utils.root;

import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RootTcpClient {
    private static final String TAG = "RootTcpClient";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 9999;

    private static RootTcpClient instance;

    // Executor mono-thread pour ne jamais bloquer le thread UI lors des envois réseau
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private Socket socket;
    private BufferedWriter writer;

    private RootTcpClient() {
        // Constructeur privé pour Singleton
    }

    public static synchronized RootTcpClient getInstance() {
        if (instance == null) {
            instance = new RootTcpClient();
        }
        return instance;
    }

    /**
     * Assure que la connexion TCP est établie et active.
     */
    private synchronized boolean ensureConnected() {
        if (socket != null && !socket.isClosed() && socket.isConnected() && writer != null) {
            return true;
        }

        try {
            CarLog.d(TAG, "Ouverture de la connexion TCP permanente vers " + HOST + ":" + PORT);
            socket = new Socket(HOST, PORT);
            // Désactive l'algorithme de Nagle pour envoyer les événements tactiles instantanément sans délai
            socket.setTcpNoDelay(true);

            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            return true;
        } catch (Exception e) {
            CarLog.e(TAG, "Impossible de se connecter au Daemon Root TCP", e);
            closeQuietly();
            return false;
        }
    }

    /**
     * Envoie une commande ou un événement tactile sur la connexion unique.
     */
    public void send(String payload) {
//        executor.execute(() -> {
//            synchronized (this) {
//                if (!ensureConnected()) {
//                    return;
//                }
//
//                try {
//                    writer.write(payload);
//                    writer.newLine(); // Important : readline() côté serveur attend une fin de ligne
//                    writer.flush();   // Envoie immédiatement le paquet
//                } catch (Exception e) {
//                    CarLog.e(TAG, "Erreur d'envoi TCP, réinitialisation du socket...", e);
//                    closeQuietly();
//                }
//            }
//        });
    }

    private void closeQuietly() {
        try {
            if (writer != null) writer.close();
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        } finally {
            writer = null;
            socket = null;
        }
    }
}