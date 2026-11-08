package edu.ec5205.alert.server;

import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.CreateAlert;
import edu.ec5205.alert.common.Messages.ErrorMessage;
import edu.ec5205.alert.common.Messages.RegisterClient;
import edu.ec5205.alert.common.Messages.Registered;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/** Owns a connection and its registration until EOF, failure, or server shutdown. */
public final class ClientHandler implements Runnable {
    private final Socket socket;
    private final ConnectionRegistry registry;
    private final Runnable onFinished;
    private final JsonMessageCodec codec = new JsonMessageCodec();

    public ClientHandler(Socket socket, ConnectionRegistry registry, Runnable onFinished) {
        this.socket = socket;
        this.registry = registry;
        this.onFinished = onFinished;
    }

    @Override
    public void run() {
        String registeredId = null;
        try (socket;
             BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
             OutputStream output = socket.getOutputStream()) {
            while (true) {
                ProtocolMessage message;
                try {
                    message = codec.read(input);
                } catch (ProtocolException e) {
                    codec.write(output, new ErrorMessage("INVALID_MESSAGE", e.getMessage(), e.requestId()));
                    return; // In particular, do not try to drain an oversized frame.
                } catch (EOFException e) {
                    codec.write(output, new ErrorMessage("INVALID_MESSAGE", "Incomplete JSON frame before EOF.", null));
                    return;
                }
                if (message == null) {
                    System.out.println("Client disconnected: " + socket.getRemoteSocketAddress());
                    return;
                }

                if (message instanceof RegisterClient registration) {
                    if (registeredId != null) {
                        codec.write(output, new ErrorMessage("INVALID_MESSAGE", "This connection is already registered.", null));
                        continue;
                    }
                    if (!registry.register(registration.clientId(), socket)) {
                        codec.write(output, new ErrorMessage("DUPLICATE_CLIENT", "Client ID is already active.", null));
                        return;
                    }
                    registeredId = registration.clientId();
                    codec.write(output, new Registered(registeredId));
                    System.out.println("Registered client " + registeredId + " from " + socket.getRemoteSocketAddress());
                    continue;
                }

                String requestId = message instanceof CreateAlert request ? request.requestId() : null;
                boolean requiresAdmin = switch (message.type()) {
                    case CREATE_ALERT, GET_CLIENTS, GET_ACKS -> true;
                    default -> false;
                };
                boolean requiresRegistration = switch (message.type()) {
                    case ACK, GET_HISTORY -> true;
                    default -> false;
                };
                if (requiresAdmin || (registeredId == null && requiresRegistration)) {
                    codec.write(output, new ErrorMessage("UNAUTHORIZED", "This request requires an authorized connection.", requestId));
                } else {
                    codec.write(output, new ErrorMessage("INVALID_MESSAGE",
                            registeredId == null ? "Send REGISTER_CLIENT first; only registration is implemented."
                                    : "This message is not supported in the registration milestone.", requestId));
                }
                if (registeredId == null) return;
            }
        } catch (IOException e) {
            System.err.println("Client I/O error from " + socket.getRemoteSocketAddress() + ": " + e.getMessage());
        } finally {
            if (registeredId != null) registry.unregister(registeredId, socket);
            onFinished.run();
        }
    }
}
