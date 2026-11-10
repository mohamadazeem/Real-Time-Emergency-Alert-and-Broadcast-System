package edu.ec5205.alert.client.model;

/**
 * Acknowledgement states for a received alert.
 */
public enum AckStatusState {
    UNACKNOWLEDGED("Unacknowledged"),
    PENDING("Acknowledging..."),
    ACKNOWLEDGED("Acknowledged"),
    FAILED("Ack Failed");

    private final String displayLabel;

    AckStatusState(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public String displayLabel() {
        return displayLabel;
    }
}

