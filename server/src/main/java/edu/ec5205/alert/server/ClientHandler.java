package edu.ec5205.alert.server;

import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.MessageType;
import edu.ec5205.alert.common.Messages.CreateAlert;
import edu.ec5205.alert.common.Messages.ErrorMessage;
import edu.ec5205.alert.common.Messages.Login;
import edu.ec5205.alert.common.Messages.LoginResult;
import edu.ec5205.alert.common.Messages.RegisterClient;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.sql.SQLException;

/** Owns a connection and its registration until EOF, failure, or server shutdown. */
public final class ClientHandler implements Runnable {
    private final Socket socket;
    private final ClientConnection connection;
    private final ConnectionRegistry registry;
    private final AdminCredentials credentials;
    private final AlertCreationService alertCreation;
    private final Runnable onFinished;
    private final JsonMessageCodec codec = new JsonMessageCodec();

    public ClientHandler(Socket socket, ConnectionRegistry registry, AdminCredentials credentials,
                         AlertCreationService alertCreation, Runnable onFinished) {
        this(new ClientConnection(socket, 32, registry::unregister), registry, credentials, alertCreation, onFinished);
    }

    public ClientHandler(ClientConnection connection, ConnectionRegistry registry, AdminCredentials credentials,
                         AlertCreationService alertCreation, Runnable onFinished) {
        this.connection = connection;
        this.socket = connection.socket();
        this.registry = registry;
        this.credentials = credentials;
        this.alertCreation = alertCreation;
        this.onFinished = onFinished;
    }

    @Override
    public void run() {
        String registeredId = null;
        ConnectionRole role = ConnectionRole.UNREGISTERED;
        BufferedInputStream input = null;
        connection.startWriter();
        try {
            input = new BufferedInputStream(socket.getInputStream());
            while (!connection.isClosed()) {
                ProtocolMessage message;
                try {
                    message = codec.read(input);
                } catch (ProtocolException e) {
                    if (e.messageType() == MessageType.CREATE_ALERT && role != ConnectionRole.ADMIN) {
                        connection.send(new ErrorMessage("UNAUTHORIZED", "CREATE_ALERT requires an authenticated admin.", e.requestId()));
                        if (role == ConnectionRole.UNREGISTERED) return;
                        continue;
                    }
                    if (e.isInvalidAlert()) {
                        connection.send(new ErrorMessage("INVALID_ALERT", e.getMessage(), e.requestId()));
                        continue;
                    }
                    connection.send(new ErrorMessage("INVALID_MESSAGE", e.getMessage(), e.requestId()));
                    return; // In particular, do not try to drain an oversized frame.
                } catch (EOFException e) {
                    connection.send(new ErrorMessage("INVALID_MESSAGE", "Incomplete JSON frame before EOF.", null));
                    return;
                }
                if (message == null) {
                    System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
                    return;
                }

                if (message instanceof Login login) {
                    if (role != ConnectionRole.UNREGISTERED) {
                        connection.send(new ErrorMessage("INVALID_MESSAGE", "This connection already has a role; open a new connection to log in.", null));
                        continue;
                    }
                    boolean success = credentials.matches(login);
                    if (success) role = ConnectionRole.ADMIN;
                    connection.send(new LoginResult(success));
                    continue;
                }

                if (message instanceof RegisterClient registration) {
                    if (role == ConnectionRole.ADMIN) {
                        connection.send(new ErrorMessage("INVALID_MESSAGE", "An admin connection cannot register as a receiver.", null));
                        continue;
                    }
                    if (role == ConnectionRole.RECEIVER) {
                        connection.send(new ErrorMessage("INVALID_MESSAGE", "This connection is already registered.", null));
                        continue;
                    }
                    if (!registry.register(registration.clientId(), connection)) {
                        if (!connection.isClosed()) connection.send(new ErrorMessage("DUPLICATE_CLIENT", "Client ID is already active.", null));
                        return;
                    }
                    registeredId = registration.clientId();
                    role = ConnectionRole.RECEIVER;
                    System.out.println("Registered client " + registeredId + " from " + socket.getRemoteSocketAddress());
                    continue;
                }

                if (message instanceof CreateAlert request && role == ConnectionRole.ADMIN) {
                    try {
                        // insert() commits before create() returns; no acceptance precedes storage.
                        connection.send(alertCreation.create(request));
                    } catch (ProtocolException e) {
                        connection.send(new ErrorMessage("INVALID_ALERT", "Alert fields do not satisfy the protocol rules.", request.requestId()));
                    } catch (SQLException e) {
                        connection.send(new ErrorMessage("INTERNAL_ERROR", "Unable to store the alert.", request.requestId()));
                    }
                    continue;
                }

                String requestId = message instanceof CreateAlert request ? request.requestId() : null;
                boolean authorized = switch (message.type()) {
                    case CREATE_ALERT, GET_CLIENTS, GET_ACKS -> role == ConnectionRole.ADMIN;
                    case GET_HISTORY -> role == ConnectionRole.ADMIN || role == ConnectionRole.RECEIVER;
                    case ACK -> role == ConnectionRole.RECEIVER;
                    default -> true; // Unexpected server-to-client types are invalid, not privileged commands.
                };
                if (!authorized) {
                    connection.send(new ErrorMessage("UNAUTHORIZED", "This request requires an authorized connection.", requestId));
                } else {
                    connection.send(new ErrorMessage("INVALID_MESSAGE",
                            role == ConnectionRole.UNREGISTERED ? "Send LOGIN or REGISTER_CLIENT first."
                                    : "This message is not implemented in the current server milestone.", requestId));
                }
                if (role == ConnectionRole.UNREGISTERED) return;
            }
        } catch (IOException e) {
            if (!connection.isClosed()) System.err.println("Client I/O error from " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        } finally {
            if (registeredId != null) registry.unregister(registeredId, connection);
            connection.finish();
            if (input != null) {
                try { input.close(); } catch (IOException e) { /* Socket is already closed. */ }
            }
            onFinished.run();
        }
    }
}
