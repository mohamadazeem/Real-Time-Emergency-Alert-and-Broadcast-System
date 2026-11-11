package edu.ec5205.alert.client.service;

import edu.ec5205.alert.client.model.*;
import edu.ec5205.alert.client.net.*;
import edu.ec5205.alert.client.storage.ClientIdentityStorage;
import edu.ec5205.alert.common.Messages.*;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Shared application workflow. Mutable state and observer callbacks belong to the EDT. */
public final class ReceiverController implements AutoCloseable {
    public record AlertView(Alert alert, AckStatusState acknowledgement) { }
    public record Snapshot(ConnectionState state, String notice, ClientIdentity identity,
                           List<AlertView> alerts, boolean busy) { }
    private enum Operation { NONE, HISTORY, ACK }
    private final AlertHistoryManager history = new AlertHistoryManager();
    private final Path identityPath;
    private final Consumer<Snapshot> observer;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> daemon(r, "receiver-commands"));
    private final ExecutorService cleanup = Executors.newSingleThreadExecutor(r -> daemon(r, "receiver-cleanup"));
    private final int timeoutMillis;
    private volatile long generation;
    private boolean closed;
    private ClientNetworkManager network;
    private ClientIdentity identity;
    private ConnectionState state = ConnectionState.DISCONNECTED;
    private String notice = "Enter the server details, then Connect.";
    private Operation operation = Operation.NONE;
    private String pendingAck;
    private int historyLimit;
    private Timer timeout;

    public ReceiverController(Path identityPath, Consumer<Snapshot> observer) { this(identityPath, observer, 8000); }
    public ReceiverController(Path identityPath, Consumer<Snapshot> observer, int timeoutMillis) {
        this.identityPath = Objects.requireNonNull(identityPath);
        this.observer = Objects.requireNonNull(observer);
        this.timeoutMillis = timeoutMillis;
    }
    private static Thread daemon(Runnable action, String name) {
        Thread thread = new Thread(action, name); thread.setDaemon(true); return thread;
    }
    private static void edt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Receiver workflow must run on EDT.");
    }
    public Snapshot snapshot() {
        edt();
        var alerts = history.getAllAlerts().stream()
                .sorted(Comparator.comparing((ReceivedAlert a) -> Instant.parse(a.createdAt())).thenComparing(ReceivedAlert::id))
                .map(a -> new AlertView(a.alert(), a.ackStatus())).toList();
        return new Snapshot(state, notice, identity, alerts, operation != Operation.NONE);
    }
    private void publish(String message) { notice = message; observer.accept(snapshot()); }
    private void post(long token, Runnable action) {
        SwingUtilities.invokeLater(() -> { if (!closed && generation == token) action.run(); });
    }
    public boolean connect(String host, int port, String name) {
        edt();
        if (closed || state == ConnectionState.CONNECTING || state == ConnectionState.CONNECTED) return false;
        if (host == null || host.isBlank() || name == null || name.isBlank() || port < 1 || port > 65535) {
            publish("Enter a host, port from 1 to 65535, and display name."); return false;
        }
        long token = ++generation;
        ClientNetworkManager connection = new ClientNetworkManager();
        network = connection; state = ConnectionState.CONNECTING; operation = Operation.NONE;
        publish("Connecting and registering...");
        connection.addListener(new ClientListener() {
            public void onRegistered(Registered registered) { post(token, () -> {
                stopTimer(); state = ConnectionState.CONNECTED; identity = connection.getCurrentIdentity();
                publish("Registered. Loading recent history..."); beginHistory();
            }); }
            public void onAlertReceived(Alert alert) { post(token, () -> {
                if (history.addAlert(alert)) publish("New " + alert.priority() + " alert: " + alert.title());
            }); }
            public void onAckResult(AckResult result) { post(token, () -> {
                if (operation != Operation.ACK || !result.alertId().equals(pendingAck)) return;
                history.updateAckStatus(pendingAck, result.success() ? AckStatusState.ACKNOWLEDGED : AckStatusState.FAILED);
                finishOperation();
                publish(result.success() ? "Acknowledgement confirmed by server." : "Acknowledgement rejected. You may retry.");
            }); }
            public void onHistoryReceived(History result) { post(token, () -> {
                if (operation != Operation.HISTORY) return;
                int added = history.mergeHistory(result.alerts()).size(); finishOperation();
                publish("History loaded: " + added + " new alert(s). Recent history is bounded; older alerts may be omitted.");
            }); }
            public void onError(ErrorMessage error) { post(token, () -> serverError(error)); }
            public void onDisconnected(String reason) { post(token, () -> retire(ConnectionState.CONNECTION_LOST, reason)); }
        });
        armTimer(() -> retire(ConnectionState.CONNECTION_LOST, "Connection/registration timed out. Reconnect to retry."));
        io.execute(() -> {
            try {
                ClientIdentity loaded = ClientIdentityStorage.loadOrCreate(identityPath, name.trim());
                if (generation != token) return;
                connection.connect(host.trim(), port, loaded);
            } catch (Exception e) { post(token, () -> retire(ConnectionState.CONNECTION_LOST,
                    "Unable to connect: " + safeFailure(e) + ". Check server details and identity file, then reconnect.")); }
        });
        return true;
    }
    private static String safeFailure(Exception e) {
        if (e instanceof java.io.UncheckedIOException) return "identity file could not be saved/read";
        if (e instanceof IllegalStateException) return "invalid identity file";
        if (e instanceof java.net.ConnectException) return "server refused the connection";
        if (e instanceof java.net.UnknownHostException) return "unknown host";
        return e.getClass().getSimpleName();
    }
    public boolean requestHistory() {
        edt();
        if (closed || state != ConnectionState.CONNECTED || operation != Operation.NONE) return false;
        beginHistory(); return true;
    }
    private void beginHistory() { operation = Operation.HISTORY; historyLimit = 100; sendHistory(); }
    private void sendHistory() {
        publish("Loading recent history (limit " + historyLimit + ")...");
        int limit = historyLimit; ClientNetworkManager connection = network;
        command(() -> connection.requestHistory(limit));
        armTimer(() -> retire(ConnectionState.CONNECTION_LOST, "History response timed out. Reconnect before retrying."));
    }
    public boolean acknowledge(String alertId) {
        edt();
        if (closed || state != ConnectionState.CONNECTED || operation != Operation.NONE) return false;
        var item = history.getAlert(alertId);
        if (item.isEmpty() || item.get().ackStatus() == AckStatusState.ACKNOWLEDGED) return false;
        operation = Operation.ACK; pendingAck = alertId;
        history.updateAckStatus(alertId, AckStatusState.PENDING);
        publish("Acknowledgement pending server confirmation...");
        ClientNetworkManager connection = network;
        command(() -> connection.sendAck(alertId));
        armTimer(() -> retire(ConnectionState.CONNECTION_LOST,
                "ACK confirmation timed out; outcome unknown. Reconnect and retry safely (server deduplicates ACKs)."));
        return true;
    }
    private void command(java.util.function.BooleanSupplier action) {
        long token = generation;
        io.execute(() -> {
            if (generation != token) return;
            if (!action.getAsBoolean()) post(token, () -> retire(ConnectionState.CONNECTION_LOST,
                    "Send failed. Reconnect before retrying."));
        });
    }
    private void serverError(ErrorMessage error) {
        if (state == ConnectionState.CONNECTING) {
            retire(ConnectionState.CONNECTION_LOST, "Registration rejected: " + error.code() + ". " + error.message()); return;
        }
        if (operation == Operation.HISTORY && error.code().equals("INVALID_MESSAGE")
                && error.message().toLowerCase(Locale.ROOT).contains("history")
                && error.message().contains("65536") && historyLimit > 1) {
            historyLimit = Math.max(1, historyLimit / 2); sendHistory(); return;
        }
        if (pendingAck != null) history.updateAckStatus(pendingAck, AckStatusState.FAILED);
        finishOperation(); publish("Server error: " + error.code() + ". " + error.message() + " Retry when connected.");
    }
    private void finishOperation() { stopTimer(); operation = Operation.NONE; pendingAck = null; }
    private void armTimer(Runnable action) {
        long token = generation;
        stopTimer(); timeout = new Timer(timeoutMillis, event -> {
            if (!closed && generation == token) action.run();
        }); timeout.setRepeats(false); timeout.start();
    }
    private void stopTimer() { if (timeout != null) { timeout.stop(); timeout = null; } }
    public void disconnect() { edt(); if (!closed) retire(ConnectionState.DISCONNECTED, "Disconnected. Reconnect keeps your identity and displayed history."); }
    private void retire(ConnectionState next, String message) {
        ++generation; // Invalidate already queued callbacks and commands first.
        ClientNetworkManager previous = network; network = null;
        if (pendingAck != null) history.updateAckStatus(pendingAck, AckStatusState.FAILED);
        finishOperation(); state = next;
        if (previous != null) cleanup.execute(previous::close);
        publish(message);
    }
    @Override public void close() {
        edt(); if (closed) return;
        retire(ConnectionState.DISCONNECTED, "Receiver closed.");
        closed = true; io.shutdownNow(); cleanup.shutdown();
    }
}
