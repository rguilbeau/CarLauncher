package com.rguilbeau.carlauncher.repository.client;

import com.rguilbeau.carlauncher.BuildConfig;
import com.rguilbeau.carlauncher.utils.DeviceEnvironment;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class NeonClient implements IClient {

    /**
     * Tag utilisé pour l'identification des messages de journalisation (logs) de cette classe.
     */
    private static final String TAG = "NeonClientDB";

    /**
     * Connexion JDBC globale et partagée vers la base de données, réouverte si fermée ou invalide.
     */
    private static Connection connection;

    /**
     * Récupère la connexion JDBC globale, en l'ouvrant ou en la réouvrant si elle est absente, fermée ou invalide.
     *
     * @return La connexion active vers la base de données.
     * @throws SQLException Si l'ouverture de la connexion échoue.
     */
    private static synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            String dbUrl = DeviceEnvironment.isProd() ? BuildConfig.PROD_DB_URL : BuildConfig.DEV_DB_URL;
            CarLog.i(TAG, "Ouverture d'une nouvelle connexion globale à la base de données...");
            connection = DriverManager.getConnection(dbUrl, BuildConfig.DB_USER, BuildConfig.DB_PASSWORD);
        }
        return connection;
    }

    /**
     * Exécute une requête SQL paramétrée sur la connexion active, et ferme/réinitialise la connexion en cas d'échec.
     *
     * @param query La requête SQL paramétrée à exécuter.
     * @param args  Les valeurs à substituer aux paramètres, dans l'ordre.
     * @return true si la requête s'est exécutée avec succès, false sinon.
     */
    @Override
    public boolean exec(String query, Object... args) {
        try (PreparedStatement stmt = getConnection().prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) {
                stmt.setObject(i + 1, args[i]);
            }

            stmt.execute();
            CarLog.i(TAG, "Query success: " + query);
            return true;

        } catch (SQLException e) {
            CarLog.e(TAG, "Query failed: " + query, e);

            synchronized (NeonClient.class) {
                try {
                    if (connection != null) {
                        connection.close();
                    }
                } catch (SQLException ignored) {
                    CarLog.e(TAG, "Close NeonClient failed: " + query, e);
                } finally {
                    connection = null;
                }
            }
            return false;
        }
    }
}
