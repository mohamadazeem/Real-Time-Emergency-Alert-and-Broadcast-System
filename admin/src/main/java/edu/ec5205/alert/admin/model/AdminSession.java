package edu.ec5205.alert.admin.model;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents the current authenticated administrator session.
 * Redacts credentials and tracks active status without exposing secrets.
 */
public final class AdminSession {
    private final String username;
    private final Instant authenticatedAt;
    private final AtomicBoolean active = new AtomicBoolean(true);

    public AdminSession(String username) {
        this.username = Objects.requireNonNull(username, "username cannot be null");
        this.authenticatedAt = Instant.now();
    }

    public String username() {
        return username;
    }

    public Instant authenticatedAt() {
        return authenticatedAt;
    }

    public boolean isActive() {
        return active.get();
    }

    public void invalidate() {
        active.set(false);
    }

    @Override
    public String toString() {
        return "AdminSession[username=" + username
                + ", authenticatedAt=" + authenticatedAt
                + ", active=" + active.get() + "]";
    }
}

