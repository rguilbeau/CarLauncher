package com.rguilbeau.carlauncher.selfupdate;

public class ReleaseItem {

    /**
     * Nom de la version publiée (ex: "1.4.0").
     */
    public final String version;

    /**
     * URL directe de téléchargement de l'APK associé à cette version.
     */
    public final String downloadUrl;

    /**
     * Taille de l'APK en octets, utilisée pour calculer la progression du téléchargement.
     */
    public final int size;

    /**
     * Construit une nouvelle release téléchargeable.
     *
     * @param version     Le nom de la version publiée.
     * @param downloadUrl L'URL directe de téléchargement de l'APK.
     * @param size        La taille de l'APK en octets.
     */
    public ReleaseItem(String version, String downloadUrl, int size) {
        this.version = version;
        this.downloadUrl = downloadUrl;
        this.size = size;
    }
}