package com.rguilbeau.carlauncher.selfupdate;

public interface UpdateListener {
    void onStatusUpdate(String message); // Pour afficher "Vérification...", "Téléchargement..."
    void onProgress(int progress);       // Pour mettre à jour une ProgressBar (0 à 100)
    void onSuccess();                    // Quand l'installation est lancée
    void onError(String error);          // En cas de problème (pas de réseau, etc.)
}
