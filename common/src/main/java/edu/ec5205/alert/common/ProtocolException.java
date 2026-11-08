package edu.ec5205.alert.common;

import java.io.IOException;

/** A frame or message violates the shared protocol. Does not include raw payloads. */
public final class ProtocolException extends IOException {
    public ProtocolException(String message) {
        super(message);
    }
}
