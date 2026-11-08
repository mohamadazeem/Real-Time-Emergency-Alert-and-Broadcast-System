package edu.ec5205.alert.common;

import java.io.IOException;

/** A frame or message violates the shared protocol. Does not include raw payloads. */
public final class ProtocolException extends IOException {
    private final String requestId;
    private final MessageType messageType;
    private final boolean invalidAlert;

    public ProtocolException(String message) {
        this(message, null);
    }

    public ProtocolException(String message, String requestId) {
        this(message, requestId, null, false);
    }

    public ProtocolException(String message, String requestId, MessageType messageType, boolean invalidAlert) {
        super(message);
        this.requestId = requestId;
        this.messageType = messageType;
        this.invalidAlert = invalidAlert;
    }

    /** Valid correlation ID recovered from a parseable but invalid message, if any. */
    public String requestId() {
        return requestId;
    }

    /** Recognized type only after version/type envelope validation succeeds. */
    public MessageType messageType() { return messageType; }

    /** True for invalid CREATE_ALERT payload fields, not its envelope/requestId. */
    public boolean isInvalidAlert() { return invalidAlert; }
}
