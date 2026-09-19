package com.rguilbeau.carlauncher.utils;

import java.io.File;
import java.util.Optional;

/**
 * Détermine si ce device est le device de production (la voiture), par opposition à un device
 * de développement/test. Un seul et même APK est installé partout ; c'est ce marqueur, propre à
 * chaque device, qui fait la différence à l'exécution (ex: quelle base de données utiliser).
 * <p>
 * Le marqueur est un fichier vide déposé dans {@code /system/etc/}, écrit uniquement par
 * {@code install.bat} (après confirmation explicite), lors du flash en priv-app. Étant dans
 * {@code /system}, il survit aux mises à jour de l'application (y compris via l'updater GitHub).
 */
public class DeviceEnvironment {

    /**
     * Chemin du fichier marqueur signalant que ce device est le device de production.
     */
    private static final String PROD_MARKER_PATH = "/system/etc/carlauncher_prod";

    /**
     * Sauvegarde du boolean pour éviter de vérifier si le fichier existe à chaque fois
     */
    private static Optional<Boolean> isProduction = Optional.empty();

    /**
     * @return true si ce device est marqué comme le device de production, false sinon
     * (device de développement/test par défaut).
     */
    public static boolean isProd() {
        if (isProduction.isEmpty()) {
            isProduction = Optional.of(new File(PROD_MARKER_PATH).exists());
        }
        return isProduction.get();
    }
}
