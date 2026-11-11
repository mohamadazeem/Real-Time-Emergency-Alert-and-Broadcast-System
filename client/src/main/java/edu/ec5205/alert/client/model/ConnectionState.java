package edu.ec5205.alert.client.model;

/**
 * Connection states for the client receiver.
 */
public enum ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    CONNECTION_LOST,
    RECONNECTING
}

