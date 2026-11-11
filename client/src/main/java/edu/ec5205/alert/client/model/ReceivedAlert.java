package edu.ec5205.alert.client.model;

import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Priority;

import java.time.Instant;
import java.util.Objects;

/**
 * Model representing an alert received by this client, tracking its local acknowledgement status.
 */
public final class ReceivedAlert {
    private final Alert alert;
    private volatile AckStatusState ackStatus;
    private final Instant receivedAt;

    public ReceivedAlert(Alert alert) {
        this(alert, AckStatusState.UNACKNOWLEDGED);
    }

    public ReceivedAlert(Alert alert, AckStatusState ackStatus) {
        this.alert = Objects.requireNonNull(alert, "alert cannot be null");
        this.ackStatus = Objects.requireNonNull(ackStatus, "ackStatus cannot be null");
        this.receivedAt = Instant.now();
    }

    public Alert alert() {
        return alert;
    }

    public String id() {
        return alert.id();
    }

    public String title() {
        return alert.title();
    }

    public String message() {
        return alert.message();
    }

    public Category category() {
        return alert.category();
    }

    public Priority priority() {
        return alert.priority();
    }

    public String createdAt() {
        return alert.createdAt();
    }

    public AckStatusState ackStatus() {
        return ackStatus;
    }

    public void setAckStatus(AckStatusState ackStatus) {
        this.ackStatus = Objects.requireNonNull(ackStatus, "ackStatus cannot be null");
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ReceivedAlert that = (ReceivedAlert) o;
        return Objects.equals(alert.id(), that.alert.id());
    }

    @Override
    public int hashCode() {
        return alert.id().hashCode();
    }
}

