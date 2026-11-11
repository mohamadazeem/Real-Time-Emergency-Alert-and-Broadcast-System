package edu.ec5205.alert.client.net;

import edu.ec5205.alert.client.model.*;
import edu.ec5205.alert.common.*;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.Messages.*;
import java.io.*;
import java.net.*;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** One socket session at a time. Blocking I/O never holds the lifecycle monitor. */
public final class ClientNetworkManager implements AutoCloseable {
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final CopyOnWriteArrayList<ClientListener> listeners = new CopyOnWriteArrayList<>();
    private final Object lifecycle = new Object();
    private volatile Session active;
    private volatile ConnectionState state = ConnectionState.DISCONNECTED;
    private volatile ClientIdentity identity;
    private boolean closed;
    private static final class Session {
        final Socket socket = new Socket();
        final Object writer = new Object();
        volatile OutputStream output;
        volatile Thread reader;
    }
    public void addListener(ClientListener listener) { if (listener != null) listeners.addIfAbsent(listener); }
    public void removeListener(ClientListener listener) { listeners.remove(listener); }
    public ConnectionState getState() { return state; }
    public boolean isConnected() { return state == ConnectionState.CONNECTED; }
    public ClientIdentity getCurrentIdentity() { return identity; }
    /** Synchronous TCP connect/send; registration confirmation arrives on the reader thread. */
    public void connect(String host, int port, ClientIdentity client) throws IOException {
        Objects.requireNonNull(client, "identity");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("Server host is required.");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535.");
        RegisterClient registration = new RegisterClient(client.clientId(), client.clientName());
        codec.encode(registration);
        Session session = new Session();
        synchronized (lifecycle) {
            if (closed) throw new IOException("Receiver network manager is closed.");
            retire(); active = session; identity = client; changeState(ConnectionState.CONNECTING);
        }
        try {
            session.socket.connect(new InetSocketAddress(host.trim(), port), 5000);
            session.socket.setTcpNoDelay(true);
            session.output = new BufferedOutputStream(session.socket.getOutputStream());
            InputStream input = new BufferedInputStream(session.socket.getInputStream());
            if (!send(session, registration)) throw new IOException("Unable to send receiver registration.");
            synchronized (lifecycle) {
                if (active != session || closed) { closeSocket(session); return; }
                session.reader = new Thread(() -> read(session, input), "client-receiver-" + client.clientId());
                session.reader.setDaemon(true); session.reader.start();
            }
        } catch (IOException e) {
            synchronized (lifecycle) {
                if (active == session) {
                    active = null; changeState(ConnectionState.DISCONNECTED);
                    notifyListeners(l -> l.onDisconnected("Connection failed. Check server host, port and availability, then reconnect."));
                }
            }
            closeSocket(session); throw e;
        }
    }
    public boolean send(ProtocolMessage message) {
        Session session = active; return session != null && send(session, message);
    }
    private boolean send(Session session, ProtocolMessage message) {
        synchronized (session.writer) {
            if (active != session || session.output == null) return false;
            try { codec.write(session.output, message); return true; }
            catch (IOException e) { lost(session, "Unable to send message; reconnect to retry."); return false; }
        }
    }
    public boolean sendAck(String alertId) { return send(new Ack(Objects.requireNonNull(alertId))); }
    public boolean requestHistory(int limit) { return send(new GetHistory(Math.max(1, Math.min(100, limit)))); }
    private void read(Session session, InputStream input) {
        try {
            while (active == session) {
                ProtocolMessage message = codec.read(input);
                if (message == null) break;
                synchronized (lifecycle) {
                    if (active != session) break;
                    if (message instanceof Registered value) {
                        if (!value.clientId().equals(identity.clientId())) throw new ProtocolException("Registration identity mismatch.");
                        changeState(ConnectionState.CONNECTED); notifyListeners(l -> l.onRegistered(value));
                    } else if (message instanceof AlertMessage value) notifyListeners(l -> l.onAlertReceived(value.alert()));
                    else if (message instanceof AckResult value) notifyListeners(l -> l.onAckResult(value));
                    else if (message instanceof History value) notifyListeners(l -> l.onHistoryReceived(value));
                    else if (message instanceof ErrorMessage value) notifyListeners(l -> l.onError(value));
                }
            }
        } catch (ProtocolException e) {
            synchronized (lifecycle) {
                if (active == session) notifyListeners(l -> l.onError(new ErrorMessage("PROTOCOL_ERROR", e.getMessage(), null)));
            }
            // Preserve integration fix: never resume inside an oversized frame.
        } catch (IOException ignored) {
            // Never include wire payloads in diagnostics.
        } finally { lost(session, "Server connection closed. Reconnect to recover recent history."); }
    }
    private void lost(Session session, String reason) {
        synchronized (lifecycle) {
            if (active != session) return;
            active = null; closeSocket(session); changeState(ConnectionState.CONNECTION_LOST);
            notifyListeners(l -> l.onDisconnected(reason));
        }
    }
    public void disconnect() {
        synchronized (lifecycle) {
            retire(); changeState(ConnectionState.DISCONNECTED); notifyListeners(l -> l.onDisconnected("Disconnected."));
        }
    }
    /** Permanent cancellation prevents queued connects reopening after window close. */
    @Override public void close() { synchronized (lifecycle) { closed = true; disconnect(); } }
    private void retire() { Session previous = active; active = null; if (previous != null) closeSocket(previous); }
    private static void closeSocket(Session session) {
        try { session.socket.close(); } catch (IOException ignored) { }
        if (session.reader != null) session.reader.interrupt();
    }
    private void changeState(ConnectionState next) {
        ConnectionState before = state; state = next;
        if (before != next) notifyListeners(l -> l.onStateChanged(before, next));
    }
    private void notifyListeners(Consumer<ClientListener> action) {
        for (ClientListener listener : listeners) {
            try { action.accept(listener); }
            catch (RuntimeException e) { System.err.println("Receiver listener failed: " + e.getClass().getSimpleName()); }
        }
    }
}
