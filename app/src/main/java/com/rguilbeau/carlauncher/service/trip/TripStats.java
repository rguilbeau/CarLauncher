package com.rguilbeau.carlauncher.service.trip;

import static android.content.Context.MODE_PRIVATE;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Statistiques de trajet (distance et temps de conduite), adossées aux SharedPreferences pour leur
 * persistance. Mutable : mise à jour en interne par {@link TripService} au fil des accumulations,
 * puis soit poussée aux {@link TripListener} abonnés, soit lue de manière synchrone via
 * {@link TripService#getFullStats()}.
 */
public class TripStats {
    /**
     * Nom du fichier de préférences partagées utilisé pour la sauvegarde des statistiques.
     */
    private static final String PREFS_NAME = "CarLauncherPrefs";
    /**
     * Clé des préférences pour stocker la distance totale parcourue.
     */
    private static final String KEY_DISTANCE = "distance";

    /**
     * Clé des préférences pour stocker le temps total de conduite accumulé.
     */
    private static final String KEY_DRIVE_TIME = "driveTime";

    /**
     * Clé des préférences pour stocker la date d'enregistrement du trajet, servant au reset journalier.
     */
    private static final String KEY_SAVED_DATE = "savedDate";
    /**
     * Distance parcourue, en mètres.
     */
    private float distanceMeters;

    /**
     * Temps de conduite accumulé, en minutes (même granularité que la persistance en base — voir
     * {@link com.rguilbeau.carlauncher.repository.dto.DailyTrip}).
     */
    private int driveTimeMinutes;

    /**
     * Jour auquel ces statistiques sont rattachées, au format "yyyy-MM-dd".
     */
    private String dayKey;
    /**
     * Le préfixe des clés des préférences pour enregistrer les différents TripStats
     */
    private final String prefixKey;
    /**
     * Gestionnaire des préférences pour l'écriture et la lecture persistante des données du trajet.
     */
    private final SharedPreferences prefs;

    /**
     * Construit un nouvel instantané de statistiques de trajet.
     *
     * @param prefs     Gestionnaire des préférences pour l'écriture et la lecture persistante des données du trajet.
     * @param prefixKey Le préfixe des clés des préférences pour enregistrer les différents TripStats
     */
    private TripStats(SharedPreferences prefs, String prefixKey) {
        this.prefs = prefs;
        this.prefixKey = prefixKey;

        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        this.distanceMeters = prefs.getFloat(prefixKey + KEY_DISTANCE, 0);
        this.driveTimeMinutes = prefs.getInt(prefixKey + KEY_DRIVE_TIME, 0);
        this.dayKey = prefs.getString(prefixKey + KEY_SAVED_DATE, today);
    }

    /**
     * Charge depuis les SharedPreferences l'instantané de statistique de trajet
     *
     * @param context Le contexte android de l'application
     * @param key     Le nom de l'instantané de statistique de trajet
     * @return L'instantané de statistique de trajet
     */
    static TripStats load(Context context, String key) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        return new TripStats(prefs, key);
    }

    /**
     * Met à zéro l'instantané de statistique (distance, temps de conduite et jour de rattachement),
     * en mémoire et dans les préférences persistées.
     */
    void reset() {
        this.distanceMeters = 0;
        this.driveTimeMinutes = 0;
        this.dayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());

        prefs.edit()
                .putFloat(prefixKey + KEY_DISTANCE, 0)
                .putInt(prefixKey + KEY_DRIVE_TIME, 0)
                .putString(prefixKey + KEY_SAVED_DATE, dayKey)
                .apply();
    }

    /**
     * Ajoute des minutes entières au temps de conduite accumulé, et persiste le nouveau total. Le
     * découpage du temps écoulé en minutes entières (et la conservation du reliquat sous la minute)
     * est à la charge de l'appelant ({@link TripService}), via son propre chronomètre.
     *
     * @param minutesToAccumulate Le nombre de minutes à rajouter.
     * @return true si le temps de conduite a été effectivement modifié, false sinon.
     */
    public boolean accumulateTime(int minutesToAccumulate) {
        if (minutesToAccumulate <= 0) {
            return false;
        }

        this.driveTimeMinutes += minutesToAccumulate;
        prefs.edit().putInt(prefixKey + KEY_DRIVE_TIME, driveTimeMinutes).apply();
        return true;
    }

    /**
     * Ajoute une portion de distance parcourue à la distance totale accumulée, et persiste le
     * nouveau total.
     *
     * @param distanceMetersToAccumulate La distance à rajouter, en mètres.
     * @return true si la distance parcourue a été effectivement modifiée, false sinon.
     */
    public boolean accumulateDistance(float distanceMetersToAccumulate) {
        if (distanceMetersToAccumulate > 0) {
            this.distanceMeters += distanceMetersToAccumulate;
            prefs.edit().putFloat(prefixKey + KEY_DISTANCE, distanceMeters).apply();
            return true;
        }
        return false;
    }

    /**
     * Récupère la distance totale parcourue.
     *
     * @return La distance parcourue, en mètres.
     */
    public float getDistanceMeters() {
        return distanceMeters;
    }

    /**
     * Récupère le temps de conduite total accumulé.
     *
     * @return Le temps de conduite, en minutes.
     */
    public int getDriveTimeMinutes() {
        return driveTimeMinutes;
    }

    /**
     * Convertit le jour de rattachement ("yyyy-MM-dd") de ces statistiques en {@link Date}.
     *
     * @return Le jour de rattachement de ces statistiques.
     * @throws ParseException Si le jour stocké est mal formé.
     */
    public Date getDate() throws ParseException {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dayKey);
    }

    /**
     * Récupère le jour de rattachement de ces statistiques, au format "yyyy-MM-dd".
     *
     * @return Le jour de rattachement de ces statistiques.
     */
    public String getDayStr() {
        return dayKey;
    }
}
