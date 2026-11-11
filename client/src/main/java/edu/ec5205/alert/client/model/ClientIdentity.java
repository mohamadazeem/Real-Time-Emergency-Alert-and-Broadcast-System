package edu.ec5205.alert.client.model;

import java.util.Objects;

/**
 * Represents the local client's persistent identity.
 */
public record ClientIdentity(String clientId, String clientName) {
    public ClientIdentity {
        Objects.requireNonNull(clientId, "clientId cannot be null");
        Objects.requireNonNull(clientName, "clientName cannot be null");
    }

    public ClientIdentity withName(String newName) {
        return new ClientIdentity(this.clientId, newName);
    }
}

