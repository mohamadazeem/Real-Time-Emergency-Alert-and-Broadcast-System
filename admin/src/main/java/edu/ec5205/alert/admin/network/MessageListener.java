package edu.ec5205.alert.admin.network;

import edu.ec5205.alert.common.ProtocolMessage;

/**
 * Callback listener for incoming messages and connection lifecycle events
 * from the AdminConnection.
 */
public interface MessageListener {

    /**
     * Invoked when a valid protocol frame is received and decoded.
     *
     * @param message the received protocol message
     */
    void onMessage(ProtocolMessage message);

    /**
     * Invoked when a protocol error or unexpected I/O exception occurs.
     *
     * @param error the underlying exception
     */
    default void onError(Throwable error) {}

    /**
     * Invoked when the connection to the server is closed.
     */
    default void onClosed() {}
}

