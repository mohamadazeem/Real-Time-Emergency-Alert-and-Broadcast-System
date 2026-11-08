package edu.ec5205.alert.server.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;

/** Share one instance across repositories. No JDBC connections remain open between calls. */
public final class SqliteDatabase {
    private final Path path;

    public SqliteDatabase(Path path) throws IOException, SQLException {
        this.path = path.toAbsolutePath().normalize();
        if (Files.isDirectory(this.path)) throw new IOException("Database path is a directory: " + this.path);
        Files.createDirectories(this.path.getParent());
        transaction(connection -> {
            for (String sql : SCHEMA) {
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.executeUpdate();
                }
            }
            return null;
        });
    }

    public static Path pathFromEnvironment(Map<String, String> environment) {
        String value = environment.getOrDefault("EMERGENCY_DB_PATH", "data/emergency-alerts.db");
        if (value.isBlank()) throw new IllegalArgumentException("EMERGENCY_DB_PATH must be a non-blank database file path.");
        return Path.of(value);
    }

    public Path path() { return path; }

    /** The shared monitor serializes all local reads/writes, including across repository types. */
    synchronized <T> T transaction(SqlWork<T> work) throws SQLException {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); } catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        }
    }

    private Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try {
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA foreign_keys = ON")) {
                statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA busy_timeout = 5000")) {
                statement.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("PRAGMA foreign_keys");
                 ResultSet result = statement.executeQuery()) {
                if (!result.next() || result.getInt(1) != 1) throw new SQLException("SQLite foreign keys could not be enabled.");
            }
            return connection;
        } catch (SQLException e) {
            try { connection.close(); } catch (SQLException closeError) { e.addSuppressed(closeError); }
            throw e;
        }
    }

    @FunctionalInterface
    interface SqlWork<T> { T run(Connection connection) throws SQLException; }

    private static final String[] SCHEMA = {
        """
        CREATE TABLE IF NOT EXISTS clients (
            clientId TEXT NOT NULL PRIMARY KEY COLLATE NOCASE,
            name TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS alerts (
            id TEXT NOT NULL PRIMARY KEY COLLATE NOCASE,
            createdAt TEXT NOT NULL,
            createdAtSort TEXT NOT NULL,
            title TEXT NOT NULL CHECK(length(title) BETWEEN 1 AND 120),
            message TEXT NOT NULL CHECK(length(message) BETWEEN 1 AND 4000),
            category TEXT NOT NULL CHECK(category IN ('FIRE','WEATHER','SECURITY','GENERAL')),
            priority TEXT NOT NULL CHECK(priority IN ('LOW','MEDIUM','CRITICAL'))
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS acknowledgements (
            alertId TEXT NOT NULL COLLATE NOCASE REFERENCES alerts(id),
            clientId TEXT NOT NULL COLLATE NOCASE REFERENCES clients(clientId),
            acknowledgedAt TEXT NOT NULL,
            acknowledgedAtSort TEXT NOT NULL,
            PRIMARY KEY (alertId, clientId)
        )
        """,
        "CREATE INDEX IF NOT EXISTS alerts_history ON alerts(createdAtSort, id)",
        "CREATE INDEX IF NOT EXISTS acknowledgements_client ON acknowledgements(clientId)"
    };
}
