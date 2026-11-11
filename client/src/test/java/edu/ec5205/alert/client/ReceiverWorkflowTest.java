package edu.ec5205.alert.client;

import edu.ec5205.alert.client.model.*;
import edu.ec5205.alert.client.net.*;
import edu.ec5205.alert.client.service.ReceiverController;
import edu.ec5205.alert.client.service.ReceiverController.Snapshot;
import edu.ec5205.alert.common.*;
import edu.ec5205.alert.common.Messages.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import javax.swing.SwingUtilities;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ReceiverWorkflowTest {
    @TempDir Path temp;
    final JsonMessageCodec codec = new JsonMessageCodec();
    static final Alert ALERT = new Alert(UUID.randomUUID().toString(), Instant.now().toString(),
            "Evacuate", "Use the marked exit.", Category.FIRE, Priority.CRITICAL);
    interface Action { void run(Socket socket) throws Exception; }
    final class Peer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0);
        final CompletableFuture<Void> done = new CompletableFuture<>();
        volatile Socket socket;
        Peer(Action action) throws IOException {
            Thread thread = new Thread(() -> {
                try (Socket accepted = listener.accept()) { socket = accepted; accepted.setSoTimeout(5000); action.run(accepted); done.complete(null); }
                catch (Throwable e) { done.completeExceptionally(e); }
            }); thread.setDaemon(true); thread.start();
        }
        int port() { return listener.getLocalPort(); }
        public void close() throws IOException { listener.close(); if (socket != null) socket.close(); }
    }
    static <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) { if (System.nanoTime() > deadline) fail("Workflow condition timed out"); Thread.sleep(10); }
    }
    final class Client implements AutoCloseable {
        final AtomicReference<Snapshot> latest = new AtomicReference<>();
        final ReceiverController controller;
        Client(int timeout) throws Exception {
            controller = ui(() -> new ReceiverController(temp.resolve(UUID.randomUUID()+".json"), state -> {
                assertTrue(SwingUtilities.isEventDispatchThread()); latest.set(state);
            }, timeout));
        }
        void connect(int port) throws Exception { assertTrue(ui(() -> controller.connect("127.0.0.1", port, "Test Receiver"))); }
        void waitState(Predicate<Snapshot> predicate) throws Exception { await(() -> latest.get() != null && predicate.test(latest.get())); }
        public void close() throws Exception { ui(() -> { controller.close(); return null; }); }
    }
    void register(Socket socket) throws Exception {
        RegisterClient registration = (RegisterClient) codec.read(socket.getInputStream());
        codec.write(socket.getOutputStream(), new Registered(registration.clientId()));
    }
    @Test void registrationHistoryDedupAndAckPendingFailureRetry() throws Exception {
        CountDownLatch reply = new CountDownLatch(1);
        try (Peer peer = new Peer(s -> {
            register(s); assertEquals(new GetHistory(100), codec.read(s.getInputStream()));
            codec.write(s.getOutputStream(), new AlertMessage(ALERT));
            codec.write(s.getOutputStream(), new History(List.of(ALERT, ALERT)));
            assertEquals(new Ack(ALERT.id()), codec.read(s.getInputStream()));
            assertTrue(reply.await(5, TimeUnit.SECONDS));
            codec.write(s.getOutputStream(), new AckResult(ALERT.id(), false));
            assertEquals(new Ack(ALERT.id()), codec.read(s.getInputStream()));
            codec.write(s.getOutputStream(), new AckResult(ALERT.id(), true));
            assertEquals(new GetHistory(100), codec.read(s.getInputStream()));
            codec.write(s.getOutputStream(), new History(List.of(ALERT)));
            assertNull(codec.read(s.getInputStream()));
        }); Client client = new Client(5000)) {
            client.connect(peer.port()); client.waitState(s -> !s.busy() && s.alerts().size() == 1);
            assertTrue(ui(() -> client.controller.acknowledge(ALERT.id())));
            assertEquals(AckStatusState.PENDING, client.latest.get().alerts().get(0).acknowledgement());
            assertFalse(ui(() -> client.controller.acknowledge(ALERT.id())));
            reply.countDown();
            client.waitState(s -> s.alerts().get(0).acknowledgement() == AckStatusState.FAILED);
            assertTrue(ui(() -> client.controller.acknowledge(ALERT.id())));
            client.waitState(s -> s.alerts().get(0).acknowledgement() == AckStatusState.ACKNOWLEDGED);
            assertFalse(ui(() -> client.controller.acknowledge(ALERT.id())));
            assertTrue(ui(client.controller::requestHistory)); client.waitState(s -> !s.busy());
            assertEquals(1, client.latest.get().alerts().size());
            assertEquals(AckStatusState.ACKNOWLEDGED, client.latest.get().alerts().get(0).acknowledgement());
            client.close(); peer.done.get(5, TimeUnit.SECONDS);
        }
    }
    @ParameterizedTest @ValueSource(booleans = {true, false})
    void oversizedHistoryHalvesToOneThenFinishes(boolean succeeds) throws Exception {
        List<Integer> limits = new CopyOnWriteArrayList<>();
        try (Peer peer = new Peer(s -> {
            register(s);
            for (int limit : List.of(100, 50, 25, 12, 6, 3, 1)) {
                GetHistory query = (GetHistory) codec.read(s.getInputStream()); limits.add(query.limit()); assertEquals(limit, query.limit());
                if (limit == 1 && succeeds) codec.write(s.getOutputStream(), new History(List.of(ALERT)));
                else codec.write(s.getOutputStream(), new ErrorMessage("INVALID_MESSAGE", "Requested history exceeds the 65536-byte frame limit. Retry with a smaller limit.", null));
            }
            assertNull(codec.read(s.getInputStream()));
        }); Client client = new Client(5000)) {
            client.connect(peer.port());
            client.waitState(s -> !s.busy() && (s.notice().startsWith("History loaded") || s.notice().startsWith("Server error")));
            assertEquals(List.of(100,50,25,12,6,3,1), limits);
            assertEquals(ConnectionState.CONNECTED, client.latest.get().state());
            assertEquals(succeeds ? 1 : 0, client.latest.get().alerts().size());
            client.close(); peer.done.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void ackTimeoutMarksUnknownOutcomeAndDisconnectsBeforeRetry() throws Exception {
        try (Peer peer = new Peer(s -> {
            register(s); codec.read(s.getInputStream()); codec.write(s.getOutputStream(), new History(List.of(ALERT)));
            assertEquals(new Ack(ALERT.id()), codec.read(s.getInputStream())); assertNull(codec.read(s.getInputStream()));
        }); Client client = new Client(500)) {
            client.connect(peer.port()); client.waitState(s -> !s.busy() && s.alerts().size() == 1);
            assertTrue(ui(() -> client.controller.acknowledge(ALERT.id())));
            client.waitState(s -> s.state() == ConnectionState.CONNECTION_LOST);
            assertEquals(AckStatusState.FAILED, client.latest.get().alerts().get(0).acknowledgement());
            assertTrue(client.latest.get().notice().contains("outcome unknown"));
            assertFalse(ui(() -> client.controller.acknowledge(ALERT.id()))); peer.done.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void queuedOldCallbackCannotReplaceNewSessionAndDuplicateConnectIsRejected() throws Exception {
        CountDownLatch sendStale = new CountDownLatch(1), callbackQueued = new CountDownLatch(1);
        try (Peer old = new Peer(s -> {
            register(s); codec.read(s.getInputStream()); codec.write(s.getOutputStream(), new History(List.of()));
            sendStale.await(5, TimeUnit.SECONDS); codec.write(s.getOutputStream(), new AlertMessage(ALERT)); codec.read(s.getInputStream());
        }); Peer replacement = new Peer(s -> {
            register(s); codec.read(s.getInputStream()); codec.write(s.getOutputStream(), new History(List.of())); codec.read(s.getInputStream());
        }); Client client = new Client(5000)) {
            client.connect(old.port()); client.waitState(s -> s.state() == ConnectionState.CONNECTED && !s.busy());
            var field = ReceiverController.class.getDeclaredField("network"); field.setAccessible(true);
            ClientNetworkManager previous = ui(() -> (ClientNetworkManager) field.get(client.controller));
            previous.addListener(new ClientListener() { public void onAlertReceived(Alert a) { callbackQueued.countDown(); } });
            ui(() -> {
                sendStale.countDown(); assertTrue(callbackQueued.await(3, TimeUnit.SECONDS));
                client.controller.disconnect();
                assertTrue(client.controller.connect("127.0.0.1", replacement.port(), "Renamed"));
                assertFalse(client.controller.connect("127.0.0.1", replacement.port(), "Duplicate attempt")); return null;
            });
            client.waitState(s -> s.state() == ConnectionState.CONNECTED && !s.busy());
            assertTrue(client.latest.get().alerts().isEmpty(), "retired queued callback must not update history");
            assertEquals("Renamed", client.latest.get().identity().clientName());
            client.close(); old.done.get(5, TimeUnit.SECONDS); replacement.done.get(5, TimeUnit.SECONDS);
        }
    }
    @Test void closeDuringStalledRegistrationCancelsSocketAndTimers() throws Exception {
        CountDownLatch registered = new CountDownLatch(1);
        try (Peer peer = new Peer(s -> { codec.read(s.getInputStream()); registered.countDown(); assertNull(codec.read(s.getInputStream())); });
             Client client = new Client(5000)) {
            client.connect(peer.port()); assertTrue(registered.await(3, TimeUnit.SECONDS));
            long before = System.nanoTime(); client.close();
            assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(1), "close must not wait for registration on EDT");
            peer.done.get(5, TimeUnit.SECONDS);
            assertFalse(ui(() -> client.controller.connect("127.0.0.1", peer.port(), "closed")));
        }
    }
    @Test void unavailableServerIsRecoverableAndOptionsPreserveConsole() throws Exception {
        int port; try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        try (Client client = new Client(1500)) { client.connect(port); client.waitState(s -> s.state() == ConnectionState.CONNECTION_LOST); assertFalse(client.latest.get().busy()); }
        var options = ClientMain.Options.parse(new String[]{"--console", "--host", "localhost", "--port", "15050", "--name", "Desk", "--id-file", "desk.json"});
        assertTrue(options.console()); assertEquals(15050, options.port()); assertEquals(Path.of("desk.json"), options.identityPath());
        assertFalse(ClientMain.Options.parse(new String[0]).console());
        assertThrows(IllegalArgumentException.class, () -> ClientMain.Options.parse(new String[]{"--port", "0"}));
        try (ClientNetworkManager manager = new ClientNetworkManager()) {
            manager.close(); assertThrows(IOException.class, () -> manager.connect("localhost", port, new ClientIdentity(UUID.randomUUID().toString(), "closed")));
        }
    }
}
