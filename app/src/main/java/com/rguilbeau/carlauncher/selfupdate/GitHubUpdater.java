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

public class GitHubUpdater {

    private static final String GITHUB_API_URL = "https://api.github.com/repos/rguilbeau/CarLauncher/releases/latest";
    private final Context context;
    private final UpdateListener listener;
    private final Handler mainHandler;
    private final ExecutorService executor;

    public GitHubUpdater(Context context, UpdateListener listener) {
        this.context = context;
        this.listener = listener;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    public void update() {
        executor.execute(() -> {
            try {
                // 1. Vérification de la version sur GitHub
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

                // 2. Extraction du lien de téléchargement de l'APK
                JSONArray assets = jsonResponse.getJSONArray("assets");
                if (assets.length() == 0) {
                    postError("Aucun fichier trouvé dans la release. (" + latestVersion + ")");
                    return;
                }

                String downloadUrl = null;
                int fileSize = 0;

                // On boucle sur tous les fichiers attachés à la release
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    String assetName = asset.getString("name");

                    // On cherche explicitement le bon fichier
                    if (assetName.equals("CarLauncher.apk")) {
                        downloadUrl = asset.getString("browser_download_url");
                        fileSize = asset.getInt("size");
                        break; // On a trouvé, on sort de la boucle
                    }
                }

                // Si la boucle s'est terminée sans trouver l'APK
                if (downloadUrl == null) {
                    postError("Le fichier CarLauncher.apk est introuvable dans cette release.");
                    return;
                }

                // 3. Téléchargement de l'APK
                postStatus("Téléchargement en cours...");
                File apkFile = downloadApk(downloadUrl, fileSize);

                if (apkFile.exists()) {
                    // 4. Installation silencieuse
                    postStatus("Installation de la mise à jour " + latestVersion + "...");
                    installApkSilently(apkFile);
                    postSuccess();
                }

            } catch (Exception e) {
                postError("Erreur : " + e.getMessage());
            }
        });
    }

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

                // Calcul du pourcentage
                if (expectedSize > 0) {
                    int progress = (int) ((total * 100) / expectedSize);
                    // On ne met à jour l'UI que si le pourcentage change pour éviter de saturer le Handler
                    if (progress > lastProgress) {
                        lastProgress = progress;
                        postProgress(progress);
                    }
                }
            }
        }
        return outputFile;
    }

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

        // Intention pour redémarrer l'application (bien qu'Android la tue souvent pendant la mise à jour)
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

    // --- Utilitaires pour communiquer avec l'UI ---

    private void postStatus(String msg) {
        mainHandler.post(() -> listener.onStatusUpdate(msg));
    }

    private void postProgress(int progress) {
        mainHandler.post(() -> listener.onProgress(progress));
    }

    private void postError(String error) {
        mainHandler.post(() -> listener.onError(error));
    }

    private void postSuccess() {
        mainHandler.post(listener::onSuccess);
    }
}