package edu.ec5205.alert.server;

import java.net.Socket;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Messages.AlertMessage;
import edu.ec5205.alert.common.Messages.Registered;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Active receiver identities. UUID keys also prevent case-variant duplicates. */
public final class ConnectionRegistry {
    private final Map<UUID, ClientConnection> connections = new HashMap<>();

    public synchronized boolean register(String clientId, ClientConnection connection) {
        UUID id = UUID.fromString(clientId);
        if (connections.containsKey(id) || connection.isClosed() || connection.receiverId() != null) return false;
        connection.bindReceiver(id);
        // Queue before publishing membership, so ALERT can never overtake REGISTERED.
        if (!connection.send(new Registered(clientId)) || connection.isClosed()) return false;
        connections.put(id, connection);
        return true;
    }

    public synchronized void unregister(String clientId, ClientConnection connection) {
        // A late cleanup must never remove a replacement connection's registration.
        connections.remove(UUID.fromString(clientId), connection);
    }

    public synchronized void unregister(ClientConnection connection) {
        if (connection.receiverId() != null) connections.remove(connection.receiverId(), connection);
    }

    public synchronized Socket find(String clientId) {
        ClientConnection connection = connections.get(UUID.fromString(clientId));
        return connection == null ? null : connection.socket();
    }

    public void broadcast(Alert alert) {
        List<ClientConnection> receivers;
        synchronized (this) { receivers = List.copyOf(connections.values()); }
        AlertMessage message = new AlertMessage(alert);
        for (ClientConnection receiver : receivers) receiver.send(message);
    }
}
