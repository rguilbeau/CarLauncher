package com.rguilbeau.carlauncher.utils.prefskey;

/**
 * Centralise l'ensemble des noms de fichiers et des clés utilisés à travers l'application
 * pour la lecture et l'écriture dans les SharedPreferences, regroupés par classe d'origine.
 * Chaque classe imbriquée préfixe ses clés pour éviter toute collision dans le fichier
 * "CarLauncherPrefs", désormais partagé par toutes les classes.
 * <p>
 * L'exécution des migrations est déléguée à {@link PerfsKeyMigration#migrate}, mais la liste des
 * migrations elle-même reste centralisée ici (voir {@link #MIGRATIONS}), au plus près des clés
 * qu'elle fait évoluer.
 */
public class PerfsKey {
    /**
     * Liste ordonnée des migrations. La migration à l'index {@code i} fait passer le schéma de
     * la version {@code i} à la version {@code i + 1} ; la version courante correspond donc
     * simplement à la taille de ce tableau.
     * <p>
     * Pour renommer une clé, changer son type de contenu, la supprimer, ou repartir de zéro :
     * ajouter une nouvelle entrée à la fin de ce tableau, jamais modifier ni supprimer une
     * migration déjà publiée. Exemple (renommage de "old_key" vers "new_key", avec passage de
     * Integer à Long) :
     * <pre>{@code
     * PerfsKeyMigration.changeType("old_key", Integer.class, Long.class),
     * PerfsKeyMigration.renameKey("old_key", "new_key"),
     * }</pre>
     */
    static final PerfsKeyMigration.Migration[] MIGRATIONS = {
            // v0 -> v1 : suppression des fichiers de préférences existants (avant leur
            // consolidation dans un fichier unique et l'introduction de ce système de migration).
            PerfsKeyMigration.clear("WeatherPrefs"),
            PerfsKeyMigration.clear(getPrefsName())
    };

    public static String getPrefsName() {
        return "CarLauncherPrefs";
    }

    /**
     * Clés des préférences trouvées dans TripStats (fichier "CarLauncherPrefs").
     */
    public static class TripStats {

        private static final String PREFIX = "trip_stats_";

        /**
         * Clé de la distance totale parcourue, en mètres (à préfixer par
         * {@link PerfsKey.TripService#getDailyStats()} ou {@link PerfsKey.TripService#getDailyStatsFull()}).
         */
        public static String getDistance() {
            return PREFIX + "distance";
        }

        /**
         * Clé du temps total de conduite accumulé, en minutes (à préfixer par
         * {@link PerfsKey.TripService#getDailyStats()} ou {@link PerfsKey.TripService#getDailyStatsFull()}).
         */
        public static String getDriveTime() {
            return PREFIX + "driveTime";
        }

        /**
         * Clé du jour ("yyyy-MM-dd") auquel ces statistiques sont rattachées, utilisée pour
         * détecter le changement de journée (à préfixer par {@link PerfsKey.TripService#getDailyStats()}
         * ou {@link PerfsKey.TripService#getDailyStatsFull()}).
         */
        public static String getSavedDate() {
            return PREFIX + "savedDate";
        }
    }

    /**
     * Clés des préférences trouvées dans TripService (fichier "CarLauncherPrefs").
     */
    public static class TripService {

        private static final String PREFIX = "trip_service_";

        /**
         * Clé de l'horodatage (timestamp en millisecondes) de la dernière coupure de contact,
         * utilisée pour déterminer si un smart reset des statistiques doit être déclenché.
         */
        public static String getLastAccOff() {
            return PREFIX + "lastAccOffTime";
        }

        /**
         * Clé (préfixe) de l'instantané de statistiques de trajet visible par l'utilisateur,
         * remis à zéro par le smart reset ou par un reset manuel.
         */
        public static String getDailyStats() {
            return PREFIX + "dailyStats";
        }

        /**
         * Clé (préfixe) de l'instantané de statistiques de trajet complet de la journée, non
         * affecté par un reset manuel et destiné à la persistance en base.
         */
        public static String getDailyStatsFull() {
            return PREFIX + "fullDailyStats";
        }
    }

    /**
     * Clés des préférences trouvées dans ShortcutStrategy (fichier "CarLauncherPrefs").
     */
    public static class ShortcutStrategy {

        private static final String PREFIX = "shortcut_strategy_";

        /**
         * Clé du package de l'application assignée à un raccourci donné, identifié par son type
         * (ex: "navigation", "phone", "music").
         *
         * @param type L'identifiant du raccourci (correspond à l'attribut XML {@code app:type}).
         */
        public static String getType(String type) {
            return PREFIX + type;
        }
    }

    /**
     * Clés des préférences trouvées dans ReaderSimulatorPeugeot407 (fichier "CarLauncherPrefs").
     */
    public static class ReaderSimulatorPeugeot407 {

        private static final String PREFIX = "reader_simulator_peugeot_407_";

        /**
         * Clé du kilométrage simulé (en km), persisté comme le serait un vrai odomètre : ne
         * repart jamais de zéro entre deux démarrages du simulateur ou de l'app.
         */
        public static String getOdometerKm() {
            return PREFIX + "odometerKm";
        }
    }

    /**
     * Clés des préférences trouvées dans CardWeather (fichier "CarLauncherPrefs").
     */
    public static class CardWeather {

        private static final String PREFIX = "card_weather_";

        /**
         * Clé de la dernière latitude GPS connue, mise en cache pour afficher la météo
         * instantanément au démarrage sans attendre un premier fix GPS.
         */
        public static String getLastLat() {
            return PREFIX + "last_lat";
        }

        /**
         * Clé de la dernière longitude GPS connue, mise en cache pour afficher la météo
         * instantanément au démarrage sans attendre un premier fix GPS.
         */
        public static String getLastLon() {
            return PREFIX + "last_lon";
        }
    }
}
