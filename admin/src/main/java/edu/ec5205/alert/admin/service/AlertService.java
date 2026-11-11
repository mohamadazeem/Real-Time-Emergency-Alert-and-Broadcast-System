package edu.ec5205.alert.admin.service;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.Messages;
import edu.ec5205.alert.common.Priority;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * Service responsible for validating alert fields and coordinating the
 * CREATE_ALERT protocol exchange over the authenticated AdminConnection.
 */
public final class AlertService {

    public static final int MAX_TITLE_LENGTH = 120;
    public static final int MAX_MESSAGE_LENGTH = 4000;
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final AdminConnection connection;
    private final AdminAuthService authService;

    public AlertService(AdminConnection connection, AdminAuthService authService) {
        this.connection = Objects.requireNonNull(connection, "connection cannot be null");
        this.authService = Objects.requireNonNull(authService, "authService cannot be null");
    }

    /**
     * Validates client-side alert fields before network transmission.
     *
     * @throws IllegalArgumentException if any field violates protocol constraints
     */
    public void validateAlertInput(String title, String message, Category category, Priority priority) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Alert title cannot be empty or whitespace-only.");
        }
        int titleLength = title.codePointCount(0, title.length());
        if (titleLength > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("Alert title exceeds " + MAX_TITLE_LENGTH + " characters (current: " + titleLength + ").");
        }

        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Alert message cannot be empty or whitespace-only.");
        }
        int messageLength = message.codePointCount(0, message.length());
        if (messageLength > MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Alert message exceeds " + MAX_MESSAGE_LENGTH + " characters (current: " + messageLength + ").");
        }

        if (category == null) {
            throw new IllegalArgumentException("Alert category must be selected.");
        }
        if (priority == null) {
            throw new IllegalArgumentException("Alert priority must be selected.");
        }
    }

    /**
     * Broadcasts an emergency alert to the server using the CREATE_ALERT protocol message.
     */
    public AlertCreationResult broadcastAlert(String title, String message, Category category, Priority priority) {
        return broadcastAlert(title, message, category, priority, DEFAULT_TIMEOUT);
    }

    public AlertCreationResult broadcastAlert(String title, String message, Category category, Priority priority, Duration timeout) {
        if (!authService.isAuthenticated()) {
            return AlertCreationResult.failure(null, "Authentication required: Admin session is not active.");
        }

        try {
            validateAlertInput(title, message, category, priority);
        } catch (IllegalArgumentException e) {
            return AlertCreationResult.failure(null, "Validation error: " + e.getMessage());
        }

        String requestId = UUID.randomUUID().toString();
        Messages.CreateAlert request = new Messages.CreateAlert(requestId, title.trim(), message.trim(), category, priority);

        try {
            ProtocolMessage response = connection.sendAndAwait(
                    request,
                    msg -> (msg instanceof Messages.AlertAccepted accepted && requestId.equals(accepted.requestId()))
                            || (msg instanceof Messages.ErrorMessage err && (requestId.equals(err.requestId()) || err.requestId() == null)),
                    timeout
            );

            if (response instanceof Messages.AlertAccepted accepted) {
                return AlertCreationResult.success(
                        accepted.requestId(),
                        accepted.alertId(),
                        "Alert accepted by server. Broadcast initiated."
                );
            } else if (response instanceof Messages.ErrorMessage error) {
                return AlertCreationResult.failure(
                        requestId,
                        "Server rejected alert [" + error.code() + "]: " + error.message()
                );
            } else {
                return AlertCreationResult.failure(requestId, "Unexpected server response type: " + response.type());
            }
        } catch (TimeoutException e) {
            return AlertCreationResult.failure(requestId, "Timed out waiting for server confirmation (" + timeout.toSeconds() + "s).");
        } catch (ProtocolException e) {
            return AlertCreationResult.failure(requestId, "Protocol error: " + e.getMessage());
        } catch (IOException e) {
            return AlertCreationResult.failure(requestId, "Network error: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AlertCreationResult.failure(requestId, "Broadcast was interrupted.");
        }
    }

    /**
     * Outcome of a CREATE_ALERT request.
     */
    public record AlertCreationResult(boolean successful, String requestId, String alertId, String message) {
        public static AlertCreationResult success(String requestId, String alertId, String message) {
            return new AlertCreationResult(true, requestId, alertId, message);
        }

        public static AlertCreationResult failure(String requestId, String message) {
            return new AlertCreationResult(false, requestId, null, message);
        }
    }
}

