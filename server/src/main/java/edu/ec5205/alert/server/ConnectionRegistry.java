package edu.ec5205.alert.server;

import java.net.Socket;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Active receiver identities. UUID keys also prevent case-variant duplicates. */
public final class ConnectionRegistry {
    private final ConcurrentMap<UUID, Socket> connections = new ConcurrentHashMap<>();

    public boolean register(String clientId, Socket socket) {
        return connections.putIfAbsent(UUID.fromString(clientId), socket) == null;
    }

    public void unregister(String clientId, Socket socket) {
        // A late cleanup must never remove a replacement connection's registration.
        connections.remove(UUID.fromString(clientId), socket);
    }

    public Socket find(String clientId) {
        return connections.get(UUID.fromString(clientId));
    }
}
