package edu.ec5205.alert.common;

/** A version-1 wire message. The codec supplies the common envelope fields. */
public interface ProtocolMessage {
    default int version() { return 1; }
    MessageType type();
}
