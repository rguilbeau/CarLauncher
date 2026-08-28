package com.rguilbeau.carlauncher.utils.log;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Gestionnaire d'exportation des journaux d'événements.
 * Compresse les fichiers de logs, les envoie vers un service de stockage temporaire (tmpfiles.org)
 * et génère un code QR contenant le lien de téléchargement direct.
 */
public class LogExporter {

    private static final String TAG = "LogExporter";
    private static final String UPLOAD_URL = "https://tmpfiles.org/api/v1/upload";

    private final Context context;
    private final OkHttpClient httpClient;
    private final ExecutorService executor;
    private final Handler mainHandler;

    /**
     * Interface de rappel pour suivre l'état de l'exportation.
     */
    public interface ExportCallback {
        /**
         * Appelée lorsque le processus est terminé avec succès.
         *
         * @param qrCode L'image Bitmap du code QR à afficher.
         * @param url    L'URL de téléchargement direct du fichier ZIP.
         */
        void onSuccess(Bitmap qrCode, String url);

        /**
         * Appelée lorsqu'une erreur survient durant le traitement.
         *
         * @param message Le message d'erreur descriptif.
         */
        void onError(String message);
    }

    /**
     * Constructeur du gestionnaire d'exportation.
     *
     * @param context Le contexte de l'application.
     */
    public LogExporter(Context context) {
        this.context = context.getApplicationContext();
        this.httpClient = new OkHttpClient();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Lance le processus asynchrone d'exportation complet : compression, upload et génération du QR Code.
     *
     * @param callback L'interface recevant le résultat sur le thread principal.
     */
    public void exportAndUploadAsync(ExportCallback callback) {
        executor.execute(() -> {
            try {
                File zipFile = createZipFile();
                if (zipFile == null) {
                    notifyError(callback, "Aucun fichier de journal trouvé à exporter.");
                    return;
                }

                uploadZipFile(zipFile, callback);
            } catch (Exception e) {
                CarLog.e(TAG, "Erreur globale lors de l'exportation", e);
                notifyError(callback, "Erreur interne lors de la préparation : " + e.getMessage());
            }
        });
    }

    /**
     * Compresse tous les fichiers de journaux présents dans le dossier dédié en un unique fichier ZIP.
     *
     * @return Le fichier ZIP généré dans le répertoire cache, ou null si le dossier est vide.
     * @throws IOException En cas d'erreur de lecture ou d'écriture sur le disque.
     */
    private File createZipFile() throws IOException {
        File logsFolder = new File(context.getFilesDir(), "Logs");
        File[] logFiles = logsFolder.listFiles((dir, name) -> name.endsWith(".log"));

        if (logFiles == null || logFiles.length == 0) {
            return null;
        }

        File zipFile = new File(context.getCacheDir(), "logs_export.zip");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
            for (File file : logFiles) {
                try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                    ZipEntry entry = new ZipEntry(file.getName());
                    zos.putNextEntry(entry);

                    byte[] buffer = new byte[2048];
                    int bytesRead;
                    while ((bytesRead = bis.read(buffer)) != -1) {
                        zos.write(buffer, 0, bytesRead);
                    }
                    zos.closeEntry();
                }
            }
        }
        return zipFile;
    }

    /**
     * Envoie le fichier compressé vers tmpfiles.org avec une durée de conservation de 48 heures.
     *
     * @param zipFile  Le fichier compressé à envoyer.
     * @param callback L'interface de retour.
     */
    private void uploadZipFile(File zipFile, ExportCallback callback) {
        RequestBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", zipFile.getName(),
                        RequestBody.create(MediaType.parse("application/zip"), zipFile))
                .addFormDataPart("expire", "172800") // Conservation maximale autorisée par l'API (48 heures)
                .build();

        Request request = new Request.Builder()
                .url(UPLOAD_URL)
                .post(requestBody)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                CarLog.e(TAG, "Échec de la requête d'upload", e);
                notifyError(callback, "Erreur réseau lors de l'envoi.");
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    String responseBody = response.body() != null ? response.body().string() : "";

                    if (!response.isSuccessful()) {
                        CarLog.e(TAG, "Erreur serveur : " + response.code() + " - " + responseBody);
                        notifyError(callback, "Erreur du serveur d'hébergement (Code " + response.code() + ").");
                        return;
                    }

                    JSONObject jsonObject = new JSONObject(responseBody);

                    if ("success".equalsIgnoreCase(jsonObject.optString("status"))) {
                        JSONObject data = jsonObject.getJSONObject("data");
                        String pageUrl = data.getString("url");

                        // Modification de l'URL pour passer en lien de téléchargement direct
                        String downloadUrl = pageUrl.replace("tmpfiles.org/", "tmpfiles.org/dl/");

                        generateAndReturnQRCode(downloadUrl, callback);
                    } else {
                        notifyError(callback, "Le service a refusé le fichier.");
                    }
                } catch (Exception e) {
                    CarLog.e(TAG, "Erreur lors de la lecture de la réponse", e);
                    notifyError(callback, "Erreur de traitement de la réponse serveur.");
                } finally {
                    response.close();
                    if (zipFile.exists()) {
                        zipFile.delete();
                    }
                }
            }
        });
    }

    /**
     * Génère une image Bitmap du code QR à partir d'une chaîne de caractères (URL).
     *
     * @param url      Le lien à encoder.
     * @param callback L'interface de retour pour transmettre le résultat à l'interface utilisateur.
     */
    private void generateAndReturnQRCode(String url, ExportCallback callback) {
        try {
            int size = 512;
            BitMatrix bitMatrix = new MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, null);
            int[] pixels = new int[size * size];

            for (int y = 0; y < size; y++) {
                int offset = y * size;
                for (int x = 0; x < size; x++) {
                    pixels[offset + x] = bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }

            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size);

            mainHandler.post(() -> callback.onSuccess(bitmap, url));

        } catch (Exception e) {
            CarLog.e(TAG, "Erreur lors de la génération du QR Code", e);
            notifyError(callback, "Erreur lors de la création du QR Code.");
        }
    }

    /**
     * Relaye une erreur vers le thread principal.
     *
     * @param callback L'interface de retour.
     * @param message  Le message d'erreur.
     */
    private void notifyError(ExportCallback callback, String message) {
        mainHandler.post(() -> callback.onError(message));
    }
}