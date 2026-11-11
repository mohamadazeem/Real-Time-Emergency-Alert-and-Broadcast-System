package edu.ec5205.alert.client.net;

import edu.ec5205.alert.client.model.ConnectionState;
import edu.ec5205.alert.common.Messages.AckResult;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Messages.ErrorMessage;
import edu.ec5205.alert.common.Messages.History;
import edu.ec5205.alert.common.Messages.Registered;

/**
 * Listener interface for network events in the client application.
 */
public interface ClientListener {
    default void onStateChanged(ConnectionState oldState, ConnectionState newState) {}
    default void onRegistered(Registered registered) {}
    default void onAlertReceived(Alert alert) {}
    default void onAckResult(AckResult ackResult) {}
    default void onHistoryReceived(History history) {}
    default void onError(ErrorMessage error) {}
    default void onDisconnected(String reason) {}
}

