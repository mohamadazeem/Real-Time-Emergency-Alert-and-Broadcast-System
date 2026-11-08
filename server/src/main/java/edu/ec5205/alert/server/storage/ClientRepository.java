package edu.ec5205.alert.server.storage;

import edu.ec5205.alert.common.Messages.ClientInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

public final class ClientRepository {
    private final SqliteDatabase database;
    public ClientRepository(SqliteDatabase database) { this.database = database; }

    /** Updates the name without replacing the row, preserving its acknowledgement references. */
    public void save(ClientInfo client) throws SQLException {
        StorageValues.id(client.clientId());
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO clients(clientId, name) VALUES (?, ?)
                    ON CONFLICT(clientId) DO UPDATE SET name = excluded.name
                    """)) {
                statement.setString(1, client.clientId());
                statement.setString(2, client.name());
                statement.executeUpdate();
                return null;
            }
        });
    }

    public Optional<ClientInfo> findById(String clientId) throws SQLException {
        StorageValues.id(clientId);
        return database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("SELECT clientId, name FROM clients WHERE clientId = ?")) {
                statement.setString(1, clientId);
                try (ResultSet result = statement.executeQuery()) {
                    return result.next() ? Optional.of(new ClientInfo(result.getString("clientId"), result.getString("name"))) : Optional.empty();
                }
            }
        });
    }
}
