package com.rguilbeau.carlauncher.selfupdate;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Gestionnaire d'auto-mise à jour de l'application via l'API GitHub.
 * Récupère les informations de la dernière version, télécharge l'APK et déclenche l'installation silencieuse.
 */
public class GitHubUpdater {
    /**
     * URL de l'API GitHub pour récupérer les informations de la dernière version (release) du projet.
     */
    private static final String GITHUB_API_URL = "https://api.github.com/repos/rguilbeau/CarLauncher/releases/latest";

    /**
     * Contexte Android de l'application, utilisé pour accéder au gestionnaire de paquets, aux répertoires locaux et aux Intents.
     */
    private final Context context;

    /**
     * Écouteur (callback) permettant de transmettre les événements de statut, de progression ou d'erreur à l'interface utilisateur.
     */
    private final UpdateListener listener;

    /**
     * Gestionnaire attaché au thread principal (UI Thread) pour s'assurer que les mises à jour de l'interface graphique sont exécutées en toute sécurité.
     */
    private final Handler mainHandler;

    /**
     * Service d'exécution asynchrone doté d'un seul thread, utilisé pour effectuer les requêtes réseau et les opérations d'écriture de fichier en arrière-plan.
     */
    private final ExecutorService executor;

    /**
     * Initialise le gestionnaire de mise à jour.
     *
     * @param context  Le contexte Android pour accéder au gestionnaire d'installation et aux dossiers.
     * @param listener L'écouteur pour notifier l'interface utilisateur de la progression et des erreurs.
     */
    public GitHubUpdater(Context context, UpdateListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    /**
     * Lance le processus complet de mise à jour en arrière-plan :
     * vérification de la release GitHub, téléchargement du fichier APK et installation.
     */
    public void update() {
        executor.execute(() -> {
            try {
                // Vérification de la version sur GitHub
                postStatus("Recherche d'une mise à jour...");

                URL url = new URL(GITHUB_API_URL);
                HttpURLConnection apiConn = (HttpURLConnection) url.openConnection();
                apiConn.setRequestMethod("GET");
                apiConn.setRequestProperty("Accept", "application/vnd.github.v3+json");

                if (apiConn.getResponseCode() != 200) {
                    postError("Erreur API GitHub : " + apiConn.getResponseCode());
                    return;
                }

                BufferedReader reader = new BufferedReader(new InputStreamReader(apiConn.getInputStream()));
                StringBuilder jsonResult = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    jsonResult.append(line);
                }
                reader.close();

                JSONObject jsonResponse = new JSONObject(jsonResult.toString());
                String latestVersion = jsonResponse.getString("tag_name");

                postStatus("Nouvelle version trouvée : " + latestVersion);

                // Extraction du lien de téléchargement de l'APK
                JSONArray assets = jsonResponse.getJSONArray("assets");
                if (assets.length() == 0) {
                    postError("Aucun fichier trouvé dans la release. (" + latestVersion + ")");
                    return;
                }

                String downloadUrl = null;
                int fileSize = 0;

                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String assetName = asset.getString("name");

                    if ("CarLauncher.apk".equals(assetName)) {
                        downloadUrl = asset.getString("browser_download_url");
                        fileSize = asset.getInt("size");
                        break;
                    }
                }

                if (downloadUrl == null) {
                    postError("Le fichier CarLauncher.apk est introuvable dans cette release.");
                    return;
                }

                // Téléchargement de l'APK
                postStatus("Téléchargement en cours...");
                File apkFile = downloadApk(downloadUrl, fileSize);

                if (apkFile.exists()) {
                    // Installation silencieuse
                    postStatus("Installation de la mise à jour " + latestVersion + "...");
                    installApkSilently(apkFile);
                    postSuccess();
                }

            } catch (Exception e) {
                postError("Erreur : " + e.getMessage());
            }
        });
    }

    /**
     * Télécharge le fichier APK depuis l'URL spécifiée et enregistre le contenu dans le cache de l'application.
     *
     * @param fileUrl      L'URL directe de téléchargement de l'APK.
     * @param expectedSize La taille attendue du fichier en octets pour calculer le pourcentage d'avancement.
     * @return Le fichier {@link File} téléchargé temporairement.
     * @throws Exception Si une erreur réseau ou d'écriture survient.
     */
    private File downloadApk(String fileUrl, int expectedSize) throws Exception {
        URL url = new URL(fileUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.connect();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new Exception("Échec du téléchargement, HTTP : " + connection.getResponseCode());
        }

        File cacheDir = new File(context.getCacheDir(), "updates");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File outputFile = new File(cacheDir, "update.apk");

        try (InputStream input = connection.getInputStream();
             FileOutputStream output = new FileOutputStream(outputFile)) {

            byte[] data = new byte[8192];
            long total = 0;
            int count;
            int lastProgress = 0;

            while ((count = input.read(data)) != -1) {
                total += count;
                output.write(data, 0, count);

                if (expectedSize > 0) {
                    int progress = (int) ((total * 100) / expectedSize);
                    if (progress > lastProgress) {
                        lastProgress = progress;
                        postProgress(progress);
                    }
                }
            }
        }
        return outputFile;
    }

    /**
     * Effectue l'installation du fichier APK via l'API {@link PackageInstaller}.
     *
     * @param apkFile Le fichier APK à installer.
     * @throws Exception Si la création de la session d'installation ou l'écriture échoue.
     */
    private void installApkSilently(File apkFile) throws Exception {
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();

        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());

        int sessionId = packageInstaller.createSession(params);
        PackageInstaller.Session session = packageInstaller.openSession(sessionId);

        try (OutputStream out = session.openWrite("UpdateSession", 0, apkFile.length());
             InputStream in = new FileInputStream(apkFile)) {

            byte[] buffer = new byte[65536];
            int c;
            while ((c = in.read(buffer)) != -1) {
                out.write(buffer, 0, c);
            }
            session.fsync(out);
        }

        Intent intent = new Intent(context, context.getClass());
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        session.commit(pendingIntent.getIntentSender());
        session.close();
    }

    /**
     * Envoie un message de changement de statut à l'interface utilisateur sur le thread principal.
     *
     * @param msg Le message de statut à afficher.
     */
    private void postStatus(String msg) {
        mainHandler.post(() -> listener.onStatusUpdate(msg));
    }

    /**
     * Transmet la progression du téléchargement à l'interface utilisateur sur le thread principal.
     *
     * @param progress Le pourcentage de progression (0 à 100).
     */
    private void postProgress(int progress) {
        mainHandler.post(() -> listener.onProgress(progress));
    }

    /**
     * Transmet un message d'erreur à l'interface utilisateur sur le thread principal.
     *
     * @param error Le message décrivant l'erreur.
     */
    private void postError(String error) {
        mainHandler.post(() -> listener.onError(error));
    }

    /**
     * Notifie l'interface utilisateur de la réussite de l'opération sur le thread principal.
     */
    private void postSuccess() {
        mainHandler.post(listener::onSuccess);
    }
}