package edu.ec5205.alert.client.net;

import edu.ec5205.alert.client.model.ClientIdentity;
import edu.ec5205.alert.client.model.ConnectionState;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.Ack;
import edu.ec5205.alert.common.Messages.AckResult;
import edu.ec5205.alert.common.Messages.AlertMessage;
import edu.ec5205.alert.common.Messages.ErrorMessage;
import edu.ec5205.alert.common.Messages.GetHistory;
import edu.ec5205.alert.common.Messages.History;
import edu.ec5205.alert.common.Messages.RegisterClient;
import edu.ec5205.alert.common.Messages.Registered;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages socket connections, message encoding/decoding, background receiving,
 * and registration lifecycle for the emergency alert client.
 */
public final class ClientNetworkManager {
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final List<ClientListener> listeners = new CopyOnWriteArrayList<>();
    private final Object writeLock = new Object();

    private volatile Socket socket;
    private volatile OutputStream outputStream;
    private volatile Thread listenerThread;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile ClientIdentity currentIdentity;
    private final AtomicBoolean isIntentionalDisconnect = new AtomicBoolean(false);

    public void addListener(ClientListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeListener(ClientListener listener) {
        listeners.remove(listener);
    }

    public ConnectionState getState() {
        return state;
    }

    public boolean isConnected() {
        return state == ConnectionState.CONNECTED;
    }

    public ClientIdentity getCurrentIdentity() {
        return currentIdentity;
    }

    /**
     * Connects to the server, registers the client, and starts background listening.
     * This method runs synchronously for the initial handshake and launches the receiver thread.
     */
    public synchronized void connect(String host, int port, ClientIdentity identity) throws IOException {
        Objects.requireNonNull(host, "host cannot be null");
        Objects.requireNonNull(identity, "identity cannot be null");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535, got: " + port);
        }

        // Close any existing connection first
        disconnect();

        this.currentIdentity = identity;
        this.isIntentionalDisconnect.set(false);
        updateState(ConnectionState.CONNECTING);

        Socket newSocket = new Socket();
        try {
            newSocket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            newSocket.setTcpNoDelay(true);

            this.socket = newSocket;
            this.outputStream = new BufferedOutputStream(newSocket.getOutputStream());

            // Start reader thread before sending registration so responses can be processed
            InputStream inputStream = new BufferedInputStream(newSocket.getInputStream());
            Thread thread = new Thread(() -> listenLoop(inputStream, newSocket), "client-receiver-" + identity.clientId());
            this.listenerThread = thread;
            thread.setDaemon(true);
            thread.start();

            // Send registration message
            send(new RegisterClient(identity.clientId(), identity.clientName()));
        } catch (IOException e) {
            updateState(ConnectionState.DISCONNECTED);
            try {
                newSocket.close();
            } catch (IOException ignored) {}
            throw e;
        }
    }

    /**
     * Sends a protocol message over the network connection safely.
     */
    public boolean send(ProtocolMessage message) {
        Objects.requireNonNull(message, "message cannot be null");
        OutputStream out = this.outputStream;
        if (out == null || socket == null || socket.isClosed()) {
            return false;
        }

        synchronized (writeLock) {
            try {
                codec.write(out, message);
                return true;
            } catch (IOException e) {
                handleConnectionLoss("Failed to send message: " + e.getMessage());
                return false;
            }
        }
    }

    /**
     * Sends an alert acknowledgement.
     */
    public boolean sendAck(String alertId) {
        Objects.requireNonNull(alertId, "alertId cannot be null");
        return send(new Ack(alertId));
    }

    /**
     * Requests alert history from the server.
     */
    public boolean requestHistory(int limit) {
        int boundedLimit = Math.max(1, Math.min(100, limit));
        return send(new GetHistory(boundedLimit));
    }

    /**
     * Gracefully disconnects the client.
     */
    public synchronized void disconnect() {
        isIntentionalDisconnect.set(true);
        updateState(ConnectionState.DISCONNECTED);

        Socket s = this.socket;
        this.socket = null;
        this.outputStream = null;

        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException ignored) {}
        }

        Thread t = this.listenerThread;
        this.listenerThread = null;
        if (t != null && t.isAlive() && Thread.currentThread() != t) {
            t.interrupt();
        }

        notifyDisconnected("User disconnected");
    }

    private void listenLoop(InputStream inputStream, Socket activeSocket) {
        try {
            while (!activeSocket.isClosed() && socket == activeSocket) {
                ProtocolMessage message;
                try {
                    message = codec.read(inputStream);
                } catch (ProtocolException e) {
                    notifyError(new ErrorMessage("PROTOCOL_ERROR", e.getMessage(), null));
                    continue;
                } catch (EOFException e) {
                    break;
                }

                if (message == null) {
                    // Clean EOF from server
                    break;
                }

                handleIncomingMessage(message);
            }
        } catch (IOException e) {
            if (!isIntentionalDisconnect.get() && socket == activeSocket) {
                handleConnectionLoss("Connection read error: " + e.getMessage());
                return;
            }
        } finally {
            if (!isIntentionalDisconnect.get() && socket == activeSocket) {
                handleConnectionLoss("Server closed the connection.");
            }
        }
    }

    private void handleIncomingMessage(ProtocolMessage message) {
        switch (message.type()) {
            case REGISTERED -> {
                Registered reg = (Registered) message;
                updateState(ConnectionState.CONNECTED);
                for (ClientListener l : listeners) {
                    try {
                        l.onRegistered(reg);
                    } catch (Exception e) {
                        System.err.println("Listener error onRegistered: " + e.getMessage());
                    }
                }
            }
            case ALERT -> {
                AlertMessage alertMsg = (AlertMessage) message;
                for (ClientListener l : listeners) {
                    try {
                        l.onAlertReceived(alertMsg.alert());
                    } catch (Exception e) {
                        System.err.println("Listener error onAlertReceived: " + e.getMessage());
                    }
                }
            }
            case ACK_RESULT -> {
                AckResult ackResult = (AckResult) message;
                for (ClientListener l : listeners) {
                    try {
                        l.onAckResult(ackResult);
                    } catch (Exception e) {
                        System.err.println("Listener error onAckResult: " + e.getMessage());
                    }
                }
            }
            case HISTORY -> {
                History history = (History) message;
                for (ClientListener l : listeners) {
                    try {
                        l.onHistoryReceived(history);
                    } catch (Exception e) {
                        System.err.println("Listener error onHistoryReceived: " + e.getMessage());
                    }
                }
            }
            case ERROR -> {
                ErrorMessage err = (ErrorMessage) message;
                notifyError(err);
            }
            default -> {
                // Ignore other message types unexpected by receiver
            }
        }
    }

    private void handleConnectionLoss(String reason) {
        if (isIntentionalDisconnect.get()) return;

        updateState(ConnectionState.CONNECTION_LOST);

        Socket s = this.socket;
        this.socket = null;
        this.outputStream = null;

        if (s != null && !s.isClosed()) {
            try {
                s.close();
            } catch (IOException ignored) {}
        }

        notifyDisconnected(reason);
    }

    private void updateState(ConnectionState newState) {
        ConnectionState oldState = this.state;
        if (oldState != newState) {
            this.state = newState;
            for (ClientListener l : listeners) {
                try {
                    l.onStateChanged(oldState, newState);
                } catch (Exception e) {
                    System.err.println("Listener error onStateChanged: " + e.getMessage());
                }
            }
        }
    }

    private void notifyError(ErrorMessage error) {
        for (ClientListener l : listeners) {
            try {
                l.onError(error);
            } catch (Exception e) {
                System.err.println("Listener error onError: " + e.getMessage());
            }
        }
    }

    private void notifyDisconnected(String reason) {
        for (ClientListener l : listeners) {
            try {
                l.onDisconnected(reason);
            } catch (Exception e) {
                System.err.println("Listener error onDisconnected: " + e.getMessage());
            }
        }
    }
}

