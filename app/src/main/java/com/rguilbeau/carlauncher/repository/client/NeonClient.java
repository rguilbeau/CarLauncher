package com.rguilbeau.carlauncher.repository.client;

import com.rguilbeau.carlauncher.BuildConfig;
import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class NeonClient implements IClient {
    private static final String TAG = "NeonClientDB";
    private static Connection connection;

    private static synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            CarLog.i(TAG, "Ouverture d'une nouvelle connexion globale à la base de données...");
            connection = DriverManager.getConnection(BuildConfig.DB_URL, BuildConfig.DB_USER, BuildConfig.DB_PASSWORD);
        }
        return connection;
    }

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
