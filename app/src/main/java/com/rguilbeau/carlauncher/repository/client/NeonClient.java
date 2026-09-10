package com.rguilbeau.carlauncher.repository.client;

import com.rguilbeau.carlauncher.utils.log.CarLog;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NeonClient implements IClient {
    private static final String TAG = "NeonClientDB";
    private static Connection connection;
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static synchronized Connection getConnection() throws SQLException {
        if (connection == null || connection.isClosed() || !connection.isValid(2)) {
            CarLog.i(TAG, "Ouverture d'une nouvelle connexion globale à la base de données...");
            connection = DriverManager.getConnection("URL", "USER", "PASSWORD");
        }
        return connection;
    }


    @Override
    public void exec(String query, Object... args) {
        executor.execute(() -> {
            try (PreparedStatement stmt = getConnection().prepareStatement(query)) {
                for (int i = 0; i < args.length; i++) {
                    stmt.setObject(i + 1, args[i]);
                }

                stmt.execute();
                CarLog.i(TAG, "Query success: " + query);

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
            }
        });
    }
}