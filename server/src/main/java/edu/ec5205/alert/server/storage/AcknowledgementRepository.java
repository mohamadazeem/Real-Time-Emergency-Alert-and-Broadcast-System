package edu.ec5205.alert.server.storage;

import edu.ec5205.alert.common.Messages.Acknowledgement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public final class AcknowledgementRepository {
    private final SqliteDatabase database;
    public AcknowledgementRepository(SqliteDatabase database) { this.database = database; }

    /** Returns true for a new acknowledgement; false for a duplicate (original time retained). */
    public boolean save(String alertId, Acknowledgement acknowledgement) throws SQLException {
        StorageValues.id(alertId);
        StorageValues.id(acknowledgement.clientId());
        String timeKey = StorageValues.timeKey(acknowledgement.acknowledgedAt());
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO acknowledgements(alertId, clientId, acknowledgedAt, acknowledgedAtSort)
                    VALUES (?, ?, ?, ?) ON CONFLICT(alertId, clientId) DO NOTHING
                    """)) {
                statement.setString(1, alertId);
                statement.setString(2, acknowledgement.clientId());
                statement.setString(3, acknowledgement.acknowledgedAt());
                statement.setString(4, timeKey);
                return statement.executeUpdate() == 1;
            }
        });
    }

    public List<Acknowledgement> findByAlertId(String alertId) throws SQLException {
        StorageValues.id(alertId);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT clientId, acknowledgedAt FROM acknowledgements WHERE alertId = ?
                    ORDER BY acknowledgedAtSort, clientId
                    """)) {
                statement.setString(1, alertId);
                try (ResultSet result = statement.executeQuery()) {
                    List<Acknowledgement> acknowledgements = new ArrayList<>();
                    while (result.next()) acknowledgements.add(new Acknowledgement(
                            result.getString("clientId"), result.getString("acknowledgedAt")));
                    return List.copyOf(acknowledgements);
                }
            }
        });
    }
}
