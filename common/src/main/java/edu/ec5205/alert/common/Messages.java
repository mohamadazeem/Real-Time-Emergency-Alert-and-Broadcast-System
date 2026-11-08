package edu.ec5205.alert.common;

import java.util.List;

/** Immutable message shapes; IDs and UTC timestamps retain their wire spelling. */
public final class Messages {
    private Messages() {}

    public record Alert(String id, String createdAt, String title, String message,
                        Category category, Priority priority) {}
    public record ClientInfo(String clientId, String name) {}
    public record Acknowledgement(String clientId, String acknowledgedAt) {}

    public record RegisterClient(String clientId, String name) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.REGISTER_CLIENT; }
    }

    public record Registered(String clientId) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.REGISTERED; }
    }

    public record Login(String username, String password) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.LOGIN; }
        @Override public String toString() { return "Login[credentials redacted]"; }
    }

    public record LoginResult(boolean success) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.LOGIN_RESULT; }
    }

    public record CreateAlert(String requestId, String title, String message, Category category, Priority priority) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.CREATE_ALERT; }
    }

    public record AlertMessage(Alert alert) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.ALERT; }
    }

    public record AlertAccepted(String requestId, String alertId) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.ALERT_ACCEPTED; }
    }

    public record Ack(String alertId) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.ACK; }
    }

    public record AckResult(String alertId, boolean success) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.ACK_RESULT; }
    }

    public record AckStatus(String alertId, String clientId, String acknowledgedAt) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.ACK_STATUS; }
    }

    public record GetClients() implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.GET_CLIENTS; }
    }

    public record ClientList(List<ClientInfo> clients) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.CLIENT_LIST; }
        public ClientList {
            clients = List.copyOf(clients);
        }
    }

    public record GetHistory(int limit) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.GET_HISTORY; }
    }

    public record History(List<Alert> alerts) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.HISTORY; }
        public History {
            alerts = List.copyOf(alerts);
        }
    }

    public record GetAcks(String alertId) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.GET_ACKS; }
    }

    public record AckList(String alertId, List<Acknowledgement> acknowledgements) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.ACK_LIST; }
        public AckList {
            acknowledgements = List.copyOf(acknowledgements);
        }
    }

    public record ErrorMessage(String code, String message, String requestId) implements ProtocolMessage {
        @Override public MessageType type() { return MessageType.ERROR; }
    }

}
