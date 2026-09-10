package com.rguilbeau.carlauncher.repository.worker;

import androidx.work.Data;

import java.sql.Date;

/**
 * Sérialise et désérialise une requête SQL et ses arguments dans un objet {@link Data},
 * seul format accepté par WorkManager pour transporter des données entre l'enqueue
 * et l'exécution du {@link androidx.work.Worker} (potentiellement après redémarrage du processus).
 */
final class QueryArgsCodec {

    private static final String KEY_QUERY = "query";
    private static final String KEY_ARG_TYPES = "arg_types";
    private static final String KEY_ARG_VALUES = "arg_values";

    private QueryArgsCodec() {
    }

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

    static String decodeQuery(Data data) {
        return data.getString(KEY_QUERY);
    }

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
