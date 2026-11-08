package edu.ec5205.alert.common;

import java.io.IOException;

/** A frame or message violates the shared protocol. Does not include raw payloads. */
public final class ProtocolException extends IOException {
    private final String requestId;

    public ProtocolException(String message) {
        this(message, null);
    }

    public ProtocolException(String message, String requestId) {
        super(message);
        this.requestId = requestId;
    }

    /** Valid correlation ID recovered from a parseable but invalid message, if any. */
    public String requestId() {
        return requestId;
    }
}
