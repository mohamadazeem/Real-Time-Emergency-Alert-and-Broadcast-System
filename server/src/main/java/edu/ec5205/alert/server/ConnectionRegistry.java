package edu.ec5205.alert.server;

import java.net.Socket;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.ProtocolMessage;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Messages.AlertMessage;
import edu.ec5205.alert.common.Messages.Registered;
import edu.ec5205.alert.common.Messages.RegisterClient;
import edu.ec5205.alert.common.Messages.ClientInfo;
import edu.ec5205.alert.common.Messages.AckStatus;
import edu.ec5205.alert.common.Messages.LoginResult;
import edu.ec5205.alert.common.Messages.ClientList;
import edu.ec5205.alert.common.Messages.ErrorMessage;
import edu.ec5205.alert.server.storage.ClientRepository;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;

/** Active receiver identities. UUID keys also prevent case-variant duplicates. */
public final class ConnectionRegistry {
    private final Map<UUID, ClientConnection> connections = new HashMap<>();
    private final Map<UUID, ClientInfo> clients = new HashMap<>();
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final Set<UUID> registering = new HashSet<>();
    private final Set<ClientConnection> admins = new HashSet<>();

    /** Reserve identity during storage, without holding the membership lock during SQL. */
    public boolean register(RegisterClient request, ClientConnection connection, ClientRepository clients) throws SQLException {
        UUID id = UUID.fromString(request.clientId());
        synchronized (this) {
            if (connections.containsKey(id) || !registering.add(id)) return false;
        }
        try {
            clients.save(new ClientInfo(request.clientId(), request.name()));
            synchronized (this) { return publish(new ClientInfo(request.clientId(), request.name()), connection); }
        } finally {
            synchronized (this) { registering.remove(id); }
        }
    }

    public synchronized boolean register(String clientId, ClientConnection connection) {
        return register(new ClientInfo(clientId, ""), connection);
    }

    synchronized boolean register(ClientInfo client, ClientConnection connection) {
        if (registering.contains(UUID.fromString(client.clientId()))) return false;
        return publish(client, connection);
    }

    private boolean publish(ClientInfo client, ClientConnection connection) {
        String clientId = client.clientId();
        UUID id = UUID.fromString(clientId);
        if (connections.containsKey(id) || connection.isClosed() || connection.receiverId() != null) return false;
        connection.bindReceiver(id);
        // Queue before publishing membership, so ALERT can never overtake REGISTERED.
        if (!connection.send(new Registered(clientId)) || connection.isClosed()) return false;
        connections.put(id, connection);
        clients.put(id, client);
        membershipChanged();
        return true;
    }

    public synchronized void unregister(String clientId, ClientConnection connection) {
        // A late cleanup must never remove a replacement connection's registration.
        UUID id = UUID.fromString(clientId);
        if (connections.remove(id, connection)) {
            clients.remove(id);
            membershipChanged();
        }
    }

    public synchronized void unregister(ClientConnection connection) {
        admins.remove(connection);
        if (connection.receiverId() != null) unregister(connection.receiverId().toString(), connection);
    }

    public synchronized void authenticateAdmin(ClientConnection connection) {
        // LOGIN_RESULT must precede ACK_STATUS and CLIENT_LIST events for this session.
        if (connection.send(new LoginResult(true)) && !connection.isClosed()) admins.add(connection);
    }

    public void notifyAdmins(AckStatus status) {
        List<ClientConnection> snapshot;
        synchronized (this) { snapshot = List.copyOf(admins); }
        for (ClientConnection admin : snapshot) admin.send(status);
    }

    /** Take and enqueue under the same lock as membership events: an old reply cannot overtake a new update. */
    public synchronized void sendClientList(ClientConnection admin) {
        if (!admins.contains(admin)) {
            admin.send(new ErrorMessage("UNAUTHORIZED", "GET_CLIENTS requires an authenticated admin.", null));
            return;
        }
        admin.send(clientListMessage());
    }

    /** Caller holds this monitor. Offers never wait for space or write to sockets. */
    private void membershipChanged() {
        if (admins.isEmpty()) return;
        ProtocolMessage message = clientListMessage();
        // Overflow cleanup can remove an admin synchronously, so iterate a copy.
        for (ClientConnection admin : List.copyOf(admins)) admin.send(message);
    }

    private ProtocolMessage clientListMessage() {
        ClientList snapshot = new ClientList(connections.entrySet().stream()
                .filter(entry -> !entry.getValue().isClosed())
                .map(entry -> clients.get(entry.getKey()))
                .sorted(Comparator.comparing(ClientInfo::clientId, String.CASE_INSENSITIVE_ORDER))
                .toList());
        try {
            codec.encode(snapshot); // Check actual serialized UTF-8 size before a writer sees it.
            return snapshot;
        } catch (ProtocolException e) {
            return new ErrorMessage("INVALID_MESSAGE",
                    "Client list exceeds the 65536-byte frame limit; this protocol has no pagination.", null);
        }
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
