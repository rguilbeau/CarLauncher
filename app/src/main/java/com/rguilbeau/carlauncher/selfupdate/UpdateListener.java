package com.rguilbeau.carlauncher.selfupdate;

/**
 * Interface de rappel (callback) permettant d'écouter les événements
 * liés au processus de mise à jour de l'application.
 */
public interface UpdateListener {

    /**
     * Appelée lors d'un changement d'état du processus de mise à jour.
     *
     * @param message Le message textuel décrivant l'étape en cours (ex: "Vérification...", "Téléchargement...").
     */
    void onStatusUpdate(String message);

    /**
     * Appelée régulièrement pour notifier l'avancement du téléchargement.
     *
     * @param progress Le pourcentage de progression, exprimé de 0 à 100.
     */
    void onProgress(int progress);

    /**
     * Appelée lorsque le téléchargement est terminé et que l'installation a été lancée avec succès.
     */
    void onSuccess();

    /**
     * Appelée lorsqu'une erreur critique survient au cours du processus (ex: problème réseau, fichier corrompu).
     *
     * @param error Le message détaillant l'erreur rencontrée.
     */
    void onError(String error);
}