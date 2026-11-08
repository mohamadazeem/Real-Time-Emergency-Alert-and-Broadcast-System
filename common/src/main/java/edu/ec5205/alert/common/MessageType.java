package edu.ec5205.alert.common;

/** Exact, case-sensitive type names from docs/PROTOCOL.md. */
public enum MessageType {
    REGISTER_CLIENT(Messages.RegisterClient.class),
    REGISTERED(Messages.Registered.class),
    LOGIN(Messages.Login.class),
    LOGIN_RESULT(Messages.LoginResult.class),
    CREATE_ALERT(Messages.CreateAlert.class),
    ALERT(Messages.AlertMessage.class),
    ALERT_ACCEPTED(Messages.AlertAccepted.class),
    ACK(Messages.Ack.class),
    ACK_RESULT(Messages.AckResult.class),
    ACK_STATUS(Messages.AckStatus.class),
    GET_CLIENTS(Messages.GetClients.class),
    CLIENT_LIST(Messages.ClientList.class),
    GET_HISTORY(Messages.GetHistory.class),
    HISTORY(Messages.History.class),
    GET_ACKS(Messages.GetAcks.class),
    ACK_LIST(Messages.AckList.class),
    ERROR(Messages.ErrorMessage.class);

    private final Class<? extends ProtocolMessage> modelClass;

    MessageType(Class<? extends ProtocolMessage> modelClass) {
        this.modelClass = modelClass;
    }

    public Class<? extends ProtocolMessage> modelClass() {
        return modelClass;
    }
}
