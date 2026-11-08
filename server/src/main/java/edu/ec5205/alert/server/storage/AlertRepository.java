package edu.ec5205.alert.server.storage;

import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Priority;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class AlertRepository {
    private final SqliteDatabase database;
    public AlertRepository(SqliteDatabase database) { this.database = database; }

    public void insert(Alert alert) throws SQLException {
        StorageValues.id(alert.id());
        StorageValues.text(alert.title(), 120);
        StorageValues.text(alert.message(), 4000);
        String timeKey = StorageValues.timeKey(alert.createdAt());
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO alerts(id, createdAt, createdAtSort, title, message, category, priority)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, alert.id());
                statement.setString(2, alert.createdAt());
                statement.setString(3, timeKey);
                statement.setString(4, alert.title());
                statement.setString(5, alert.message());
                statement.setString(6, alert.category().name());
                statement.setString(7, alert.priority().name());
                statement.executeUpdate();
                return null;
            }
        });
    }

    public Optional<Alert> findById(String alertId) throws SQLException {
        StorageValues.id(alertId);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM alerts WHERE id = ?")) {
                statement.setString(1, alertId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(readAlert(result)) : Optional.empty();
                }
            }
        });
    }

    /** Select the newest bounded subset first, then return that subset oldest to newest. */
    public List<Alert> recent(int limit) throws SQLException {
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("History limit must be between 1 and 100.");
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT * FROM (
                        SELECT * FROM alerts ORDER BY createdAtSort DESC, id DESC LIMIT ?
                    ) ORDER BY createdAtSort ASC, id ASC
                    """)) {
                statement.setInt(1, limit);
                try (ResultSet result = statement.executeQuery()) {
                    List<Alert> alerts = new ArrayList<>();
                    while (result.next()) alerts.add(readAlert(result));
                    return List.copyOf(alerts);
                }
            }
        });
    }

    private static Alert readAlert(ResultSet result) throws SQLException {
        return new Alert(result.getString("id"), result.getString("createdAt"), result.getString("title"),
                result.getString("message"), Category.valueOf(result.getString("category")),
                Priority.valueOf(result.getString("priority")));
    }
}
