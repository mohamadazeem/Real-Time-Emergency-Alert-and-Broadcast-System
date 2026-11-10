package edu.ec5205.alert.admin.service;

import edu.ec5205.alert.admin.model.AdminSession;
import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.common.MessageType;
import edu.ec5205.alert.common.Messages;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * Service that manages Admin authentication, session lifecycle,
 * and post-login queries over the underlying AdminConnection.
 */
public final class AdminAuthService {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final AdminConnection connection;
    private volatile AdminSession currentSession;

    public AdminAuthService(AdminConnection connection) {
        this.connection = Objects.requireNonNull(connection, "connection cannot be null");
    }

    /**
     * Attempts to log in with the specified administrator credentials.
     * Credentials are never logged or stored in plain text.
     */
    public LoginOutcome login(String username, String password) {
        return login(username, password, DEFAULT_TIMEOUT);
    }

    public LoginOutcome login(String username, String password, Duration timeout) {
        if (username == null || username.isBlank()) {
            return LoginOutcome.failure("Username cannot be blank.");
        }
        if (password == null) {
            return LoginOutcome.failure("Password cannot be null.");
        }
        if (!connection.isConnected()) {
            return LoginOutcome.failure("Not connected to server.");
        }
        if (isAuthenticated()) {
            return LoginOutcome.failure("Session is already authenticated as '" + currentSession.username() + "'.");
        }

        try {
            Messages.Login loginMessage = new Messages.Login(username.trim(), password);
            ProtocolMessage response = connection.sendAndAwait(
                    loginMessage,
                    msg -> msg.type() == MessageType.LOGIN_RESULT || msg.type() == MessageType.ERROR,
                    timeout
            );

            if (response instanceof Messages.LoginResult result) {
                if (result.success()) {
                    this.currentSession = new AdminSession(username.trim());
                    return LoginOutcome.success("Authentication successful! Session established for '" + username.trim() + "'.");
                } else {
                    return LoginOutcome.failure("Authentication failed: Invalid username or password.");
                }
            } else if (response instanceof Messages.ErrorMessage error) {
                return LoginOutcome.failure("Server rejected login (" + error.code() + "): " + error.message());
            } else {
                return LoginOutcome.failure("Unexpected server response type: " + response.type());
            }
        } catch (TimeoutException e) {
            return LoginOutcome.failure("Login timed out waiting for server response (" + timeout.toSeconds() + "s).");
        } catch (ProtocolException e) {
            return LoginOutcome.failure("Protocol error during authentication: " + e.getMessage());
        } catch (IOException e) {
            return LoginOutcome.failure("Network error during authentication: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LoginOutcome.failure("Authentication was interrupted.");
        }
    }

    /**
     * Returns true if there is an active authenticated session on an open connection.
     */
    public boolean isAuthenticated() {
        return currentSession != null && currentSession.isActive() && connection.isConnected();
    }

    public Optional<AdminSession> getSession() {
        return Optional.ofNullable(currentSession);
    }

    /**
     * Invalidates the local session.
     */
    public void logout() {
        if (currentSession != null) {
            currentSession.invalidate();
            currentSession = null;
        }
    }

    /**
     * Post-login connectivity test: requests the list of connected receivers.
     */
    public List<Messages.ClientInfo> requestConnectedClients(Duration timeout)
            throws IOException, TimeoutException, InterruptedException, ProtocolException {

        if (!isAuthenticated()) {
            throw new IllegalStateException("Authentication required: Send LOGIN first.");
        }

        ProtocolMessage response = connection.sendAndAwait(
                new Messages.GetClients(),
                msg -> msg.type() == MessageType.CLIENT_LIST || msg.type() == MessageType.ERROR,
                timeout
        );

        if (response instanceof Messages.ClientList clientList) {
            return clientList.clients();
        } else if (response instanceof Messages.ErrorMessage error) {
            throw new ProtocolException("Server returned error (" + error.code() + "): " + error.message());
        } else {
            throw new ProtocolException("Unexpected response type: " + response.type());
        }
    }

    /**
     * Post-login query: requests acknowledgements for a specific alert by its ID.
     */
    public List<Messages.Acknowledgement> requestAcknowledgements(String alertId, Duration timeout)
            throws IOException, TimeoutException, InterruptedException, ProtocolException {

        if (!isAuthenticated()) {
            throw new IllegalStateException("Authentication required: Send LOGIN first.");
        }
        Objects.requireNonNull(alertId, "alertId cannot be null");

        ProtocolMessage response = connection.sendAndAwait(
                new Messages.GetAcks(alertId),
                msg -> msg.type() == MessageType.ACK_LIST || msg.type() == MessageType.ERROR,
                timeout
        );

        if (response instanceof Messages.AckList ackList) {
            return ackList.acknowledgements();
        } else if (response instanceof Messages.ErrorMessage error) {
            throw new ProtocolException("Server returned error (" + error.code() + "): " + error.message());
        } else {
            throw new ProtocolException("Unexpected response type: " + response.type());
        }
    }

    /**
     * Post-login connectivity test: requests recent alert history.
     */
    public List<Messages.Alert> requestHistory(int limit, Duration timeout)
            throws IOException, TimeoutException, InterruptedException, ProtocolException {

        if (!isAuthenticated()) {
            throw new IllegalStateException("Authentication required: Send LOGIN first.");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("History limit must be between 1 and 100: " + limit);
        }

        ProtocolMessage response = connection.sendAndAwait(
                new Messages.GetHistory(limit),
                msg -> msg.type() == MessageType.HISTORY || msg.type() == MessageType.ERROR,
                timeout
        );

        if (response instanceof Messages.History history) {
            return history.alerts();
        } else if (response instanceof Messages.ErrorMessage error) {
            throw new ProtocolException("Server returned error (" + error.code() + "): " + error.message());
        } else {
            throw new ProtocolException("Unexpected response type: " + response.type());
        }
    }

    /**
     * Result of an authentication attempt.
     */
    public record LoginOutcome(boolean successful, String message) {
        public static LoginOutcome success(String message) {
            return new LoginOutcome(true, message);
        }
        public static LoginOutcome failure(String message) {
            return new LoginOutcome(false, message);
        }
    }
}

