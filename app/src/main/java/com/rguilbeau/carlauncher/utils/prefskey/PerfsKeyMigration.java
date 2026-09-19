package com.rguilbeau.carlauncher.utils.prefskey;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.Set;

/**
 * Exécute et outille les migrations du schéma des préférences déclarées dans
 * {@link PerfsKey#MIGRATIONS}. Permet de renommer une clé ou de changer le type de son contenu
 * sans jamais avoir à vider les préférences existantes ni à inventer une nouvelle clé arbitraire.
 */
public class PerfsKeyMigration {

    /**
     * Clé technique stockant la version du schéma des préférences actuellement appliquée.
     */
    private static final String SCHEMA_VERSION_KEY = "schema_version";

    /**
     * Une étape de migration : fait passer les préférences d'une version de schéma à la suivante.
     * Chaque étape doit être idempotente (ne pas planter si elle est rejouée, ou si les clés
     * qu'elle attend sont déjà absentes/déjà migrées).
     */
    public interface Migration {
        /**
         * Applique la transformation.
         *
         * @param context    Le contexte Android, permettant d'accéder à un fichier de préférences
         *                   autre que celui en cours de migration (voir {@link #clear(String)}).
         * @param editor     L'éditeur du fichier de préférences en cours de migration, à utiliser
         *                   pour écrire/supprimer ses clés.
         * @param allEntries Un instantané de toutes les entrées actuelles de ce fichier ({@link SharedPreferences#getAll()}),
         *                   à utiliser pour lire une ancienne clé sans risque de {@link ClassCastException}
         *                   si son type de contenu a changé.
         */
        void apply(Context context, SharedPreferences.Editor editor, Map<String, ?> allEntries);
    }

    /**
     * Exécute, si nécessaire, toutes les migrations déclarées dans {@link PerfsKey#MIGRATIONS} pour
     * amener les préférences à la dernière version de schéma connue. À appeler une seule fois au
     * démarrage de l'application (ex: {@code Application#onCreate}), avant toute lecture des préférences.
     * <p>
     * Chaque migration est commitée individuellement (et la version de schéma avancée en même
     * temps) afin qu'un crash en cours de route ne rejoue pas une migration déjà appliquée.
     *
     * @param context Le contexte Android utilisé pour accéder aux préférences.
     */
    public static void migrate(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PerfsKey.getPrefsName(), Context.MODE_PRIVATE);
        Migration[] migrations = PerfsKey.MIGRATIONS;

        for (int version = prefs.getInt(SCHEMA_VERSION_KEY, 0); version < migrations.length; version++) {
            SharedPreferences.Editor editor = prefs.edit();
            migrations[version].apply(context, editor, prefs.getAll());
            editor.putInt(SCHEMA_VERSION_KEY, version + 1);
            editor.commit();
        }
    }

    /**
     * @param context Le contexte Android utilisé pour accéder aux préférences.
     * @return La version de schéma actuellement persistée dans les préférences, ou 0 si aucune
     * migration n'a encore été appliquée.
     */
    public static int getSchemaVersion(Context context) {
        return context.getSharedPreferences(PerfsKey.getPrefsName(), Context.MODE_PRIVATE).getInt(SCHEMA_VERSION_KEY, 0);
    }

    /**
     * Construit une migration qui renomme une clé, en conservant sa valeur telle quelle.
     * À combiner avec {@link #changeType(String, Class, Class)} (deux étapes distinctes) si le
     * renommage s'accompagne aussi d'un changement de type.
     *
     * @param oldKey L'ancien nom de la clé.
     * @param newKey Le nouveau nom de la clé.
     * @return La migration correspondante.
     */
    public static Migration renameKey(String oldKey, String newKey) {
        return (context, editor, allEntries) -> {
            Object value = allEntries.get(oldKey);
            if (value != null) {
                putAny(editor, newKey, value);
            }
            editor.remove(oldKey);
        };
    }

    /**
     * Construit une migration qui change le type de contenu d'une clé, dont le nom reste inchangé.
     * Types supportés (dans les deux sens) : {@link Integer}, {@link Long}, {@link Float},
     * {@link Boolean} et {@link String}.
     *
     * @param key      La clé dont le type de contenu change.
     * @param fromType Le type actuellement stocké sous cette clé.
     * @param toType   Le nouveau type à stocker sous cette clé.
     * @return La migration correspondante.
     */
    public static Migration changeType(String key, Class<?> fromType, Class<?> toType) {
        return (context, editor, allEntries) -> {
            Object value = allEntries.get(key);
            if (value != null && fromType.isInstance(value)) {
                putAny(editor, key, convert(value, toType));
            }
        };
    }

    /**
     * Construit une migration qui supprime une clé.
     *
     * @param key La clé à supprimer.
     * @return La migration correspondante.
     */
    public static Migration removeKey(String key) {
        return (context, editor, allEntries) -> editor.remove(key);
    }

    /**
     * Construit une migration qui supprime l'intégralité d'un fichier de préférences, qu'il
     * s'agisse du fichier en cours de migration ({@link PerfsKey#getPrefsName()}) ou d'un autre
     * fichier, y compris un fichier legacy qui n'est plus utilisé par l'application (voir
     * {@link PerfsKey#MIGRATIONS} pour un exemple). À utiliser en dernier recours (ex:
     * incompatibilité structurelle trop lourde à migrer clé par clé), plutôt qu'un nettoyage
     * manuel arbitraire du fichier.
     *
     * @param prefsFileName Le nom du fichier de préférences à supprimer entièrement.
     * @return La migration correspondante.
     */
    public static Migration clear(String prefsFileName) {
        return (context, editor, allEntries) ->
                context.getSharedPreferences(prefsFileName, Context.MODE_PRIVATE).edit().clear().commit();
    }

    /**
     * Convertit une valeur vers le type de préférence cible.
     *
     * @param value  La valeur à convertir (Integer, Long, Float, Boolean ou String).
     * @param toType Le type cible.
     * @return La valeur convertie.
     */
    private static Object convert(Object value, Class<?> toType) {
        if (toType == Integer.class) {
            return value instanceof Number ? ((Number) value).intValue() : Integer.parseInt(value.toString());
        } else if (toType == Long.class) {
            return value instanceof Number ? ((Number) value).longValue() : Long.parseLong(value.toString());
        } else if (toType == Float.class) {
            return value instanceof Number ? ((Number) value).floatValue() : Float.parseFloat(value.toString());
        } else if (toType == Boolean.class) {
            return value instanceof Boolean ? value : Boolean.parseBoolean(value.toString());
        } else if (toType == String.class) {
            return value.toString();
        } else {
            throw new IllegalArgumentException("Unsupported preference type: " + toType);
        }
    }

    /**
     * Écrit une valeur dont le type n'est connu qu'à l'exécution, en la redirigeant vers la
     * méthode {@code putXxx} adaptée à son type réel.
     *
     * @param editor L'éditeur des préférences.
     * @param key    La clé à écrire.
     * @param value  La valeur à écrire (Integer, Long, Float, Boolean, String ou Set&lt;String&gt;).
     */
    @SuppressWarnings("unchecked")
    private static void putAny(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Integer) {
            editor.putInt(key, (Integer) value);
        } else if (value instanceof Long) {
            editor.putLong(key, (Long) value);
        } else if (value instanceof Float) {
            editor.putFloat(key, (Float) value);
        } else if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Set) {
            editor.putStringSet(key, (Set<String>) value);
        } else {
            throw new IllegalArgumentException("Unsupported preference type: " + value.getClass());
        }
    }
}
