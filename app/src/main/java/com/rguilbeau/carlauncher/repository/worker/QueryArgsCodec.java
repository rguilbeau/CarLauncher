package com.rguilbeau.carlauncher.repository.worker;

import androidx.work.Data;

import java.sql.Date;

/**
 * Sérialise et désérialise une requête SQL et ses arguments dans un objet {@link Data},
 * seul format accepté par WorkManager pour transporter des données entre l'enqueue
 * et l'exécution du {@link androidx.work.Worker} (potentiellement après redémarrage du processus).
 */
final class QueryArgsCodec {

    /**
     * Clé sous laquelle la requête SQL est stockée dans l'objet {@link Data}.
     */
    private static final String KEY_QUERY = "query";

    /**
     * Clé sous laquelle le tableau des types d'arguments est stocké dans l'objet {@link Data}.
     */
    private static final String KEY_ARG_TYPES = "arg_types";

    /**
     * Clé sous laquelle le tableau des valeurs d'arguments (sérialisées en chaînes) est stocké dans l'objet {@link Data}.
     */
    private static final String KEY_ARG_VALUES = "arg_values";

    /**
     * Constructeur privé pour empêcher l'instanciation de cette classe utilitaire.
     */
    private QueryArgsCodec() {
    }

    /**
     * Encode une requête SQL et ses arguments typés dans un objet {@link Data} transportable par WorkManager.
     *
     * @param query La requête SQL paramétrée.
     * @param args  Les valeurs à substituer aux paramètres, dans l'ordre.
     * @return L'objet {@link Data} encodant la requête et ses arguments.
     */
    static Data encode(String query, Object[] args) {
        String[] types = new String[args.length];
        String[] values = new String[args.length];

        for (int i = 0; i < args.length; i++) {
            Object arg = args[i];

            if (arg instanceof Date) {
                types[i] = "DATE";
                values[i] = String.valueOf(((Date) arg).getTime());
            } else if (arg instanceof Integer) {
                types[i] = "INT";
                values[i] = String.valueOf(arg);
            } else if (arg instanceof Long) {
                types[i] = "LONG";
                values[i] = String.valueOf(arg);
            } else if (arg instanceof Double) {
                types[i] = "DOUBLE";
                values[i] = String.valueOf(arg);
            } else if (arg instanceof Float) {
                types[i] = "FLOAT";
                values[i] = String.valueOf(arg);
            } else if (arg instanceof Boolean) {
                types[i] = "BOOL";
                values[i] = String.valueOf(arg);
            } else {
                types[i] = "STRING";
                values[i] = String.valueOf(arg);
            }
        }

        return new Data.Builder()
                .putString(KEY_QUERY, query)
                .putStringArray(KEY_ARG_TYPES, types)
                .putStringArray(KEY_ARG_VALUES, values)
                .build();
    }

    /**
     * Extrait la requête SQL encodée dans l'objet {@link Data}.
     *
     * @param data L'objet {@link Data} reçu par le worker.
     * @return La requête SQL, ou null si absente.
     */
    static String decodeQuery(Data data) {
        return data.getString(KEY_QUERY);
    }

    /**
     * Reconstruit le tableau d'arguments typés à partir de l'objet {@link Data}.
     *
     * @param data L'objet {@link Data} reçu par le worker.
     * @return Le tableau des arguments désérialisés, dans leur ordre d'origine.
     */
    static Object[] decodeArgs(Data data) {
        String[] types = data.getStringArray(KEY_ARG_TYPES);
        String[] values = data.getStringArray(KEY_ARG_VALUES);

        if (types == null || values == null) {
            return new Object[0];
        }

        Object[] args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            switch (types[i]) {
                case "DATE":
                    args[i] = new Date(Long.parseLong(values[i]));
                    break;
                case "INT":
                    args[i] = Integer.valueOf(values[i]);
                    break;
                case "LONG":
                    args[i] = Long.valueOf(values[i]);
                    break;
                case "DOUBLE":
                    args[i] = Double.valueOf(values[i]);
                    break;
                case "FLOAT":
                    args[i] = Float.valueOf(values[i]);
                    break;
                case "BOOL":
                    args[i] = Boolean.valueOf(values[i]);
                    break;
                default:
                    args[i] = values[i];
                    break;
            }
        }
        return args;
    }
}
