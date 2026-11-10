package edu.ec5205.alert.server.storage;

import edu.ec5205.alert.common.*;
import edu.ec5205.alert.common.Messages.*;
import edu.ec5205.alert.server.*;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** Real sockets exercise authorization, repository ordering, and outbound UTF-8 framing together. */
@Timeout(20)
class HistoryTest {
    @TempDir Path directory;
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final List<Peer> peers = new ArrayList<>();
    private final AdminCredentials credentials = AdminCredentials.fromEnvironment(Map.of(
            "EMERGENCY_ADMIN_USERNAME", "test-admin", "EMERGENCY_ADMIN_PASSWORD", "test-password"));
    private SqliteDatabase database;
    private AlertRepository alerts;
    private ServerSocket listener;
    private ExecutorService workers;

    @BeforeEach void start() throws Exception {
        database = new SqliteDatabase(directory.resolve("history.db"));
        alerts = new AlertRepository(database);
        listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        workers = Executors.newFixedThreadPool(8);
    }

    @AfterEach void stop() throws Exception {
        for (Peer peer : peers) { peer.socket.close(); peer.server.close(); }
        listener.close(); workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void adminAndReceiverCanReadEmptyHistoryAndStayConnected(boolean admin) throws Exception {
        Peer peer = authorized(admin);
        for (int limit : List.of(1, 100)) assertEquals(new History(List.of()), history(peer, limit));
    }

    @Test void unregisteredAndFailedLoginCannotReadEvenWhileAnotherAdminIsAuthenticated() throws Exception {
        authorized(true);
        Peer stranger = connect(), failed = connect();
        failed.send(new Login("test-admin", "incorrect")); assertEquals(new LoginResult(false), failed.read());
        for (Peer peer : List.of(stranger, failed)) {
            peer.send(new GetHistory(1)); error(peer.read(), "UNAUTHORIZED");
            assertNull(peer.read()); peer.task.get(3, TimeUnit.SECONDS);
        }
    }

    @Test void invalidLimitOnUnregisteredConnectionIsUnauthorized() throws Exception {
        Peer peer = connect();
        peer.raw("{\"version\":1,\"type\":\"GET_HISTORY\",\"limit\":0}");
        error(peer.read(), "UNAUTHORIZED"); assertNull(peer.read());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "101", "1.5", "\"1\"", "null", "true", "2147483648", "missing"})
    void invalidLimitsReturnInvalidMessageAndCleanUpOnlyTheRequestingConnection(String limit) throws Exception {
        Peer invalid = authorized(false), healthy = authorized(true);
        invalid.raw("{\"version\":1,\"type\":\"GET_HISTORY\"" +
                (limit.equals("missing") ? "" : ",\"limit\":" + limit) + "}");
        error(invalid.read(), "INVALID_MESSAGE"); assertNull(invalid.read());
        invalid.task.get(3, TimeUnit.SECONDS); assertNull(registry.find(invalid.id));
        assertEquals(new History(List.of()), history(healthy, 1));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void newestNSelectionUsesTimestampThenIdAndPreservesStoredFields(boolean admin) throws Exception {
        Alert oldest = alert(1, "2026-11-09T00:00:00Z", "Oldest");
        Alert tieLow = alert(2, "2026-11-09T00:00:00.1Z", "Quoted 'text'\nLine two");
        Alert tieHigh = alert(3, "2026-11-09T00:00:00.100000000+00:00", "caf\u00e9 \uD83D\uDEA8");
        Alert newest = alert(4, "2026-11-09T00:00:00.100000001Z", "Newest");
        for (Alert alert : List.of(newest, tieHigh, oldest, tieLow)) alerts.insert(alert);
        Peer peer = authorized(admin);
        assertEquals(new History(List.of(newest)), history(peer, 1));
        assertEquals(new History(List.of(tieHigh, newest)), history(peer, 2));
        assertEquals(new History(List.of(tieLow, tieHigh, newest)), history(peer, 3));
        assertEquals(new History(List.of(oldest, tieLow, tieHigh, newest)), history(peer, 100));
    }

    @Test void maximumLimitReturnsOnlyNewest100Of101Rows() throws Exception {
        List<Alert> all = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            Alert alert = alert(index, "2026-11-09T00:00:00Z", "Body");
            alerts.insert(alert); all.add(alert);
        }
        assertEquals(new History(all.subList(1, 101)), history(authorized(true), 100));
    }

    @Test void reopeningDatabaseAndNewHandlersRetainHistory() throws Exception {
        Alert saved = alert(1, "2026-11-09T00:00:00.123456789Z", "Persisted \uD83D\uDEA8");
        alerts.insert(saved);
        Peer first = authorized(false); assertEquals(new History(List.of(saved)), history(first, 100));
        first.socket.close(); first.task.get(3, TimeUnit.SECONDS);
        database = new SqliteDatabase(database.path()); alerts = new AlertRepository(database);
        assertEquals(new History(List.of(saved)), history(authorized(true), 100));
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void storageFailureIsGenericNeverEmptyAndSameConnectionCanRecover(boolean admin) throws Exception {
        Alert saved = alert(1, "2026-11-09T00:00:00Z", "Body"); alerts.insert(saved);
        Peer peer = authorized(admin);
        sql("ALTER TABLE alerts RENAME TO private_history_storage_details");
        peer.send(new GetHistory(10));
        ErrorMessage failure = error(peer.read(), "INTERNAL_ERROR");
        assertEquals("Unable to retrieve alert history.", failure.message());
        assertFalse(codec.encode(failure).contains("private_history_storage_details"));
        assertFalse(codec.encode(failure).contains(database.path().toString()));
        sql("ALTER TABLE private_history_storage_details RENAME TO alerts");
        assertEquals(new History(List.of(saved)), history(peer, 10));
    }

    @Test void historyDoesNotAcknowledgeAndLiveAndHistoryShareIds() throws Exception {
        Peer admin = authorized(true), receiver = authorized(false);
        admin.send(new CreateAlert(UUID.randomUUID().toString(), "Test", "Body", Category.GENERAL, Priority.LOW));
        AlertAccepted accepted = assertInstanceOf(AlertAccepted.class, admin.read());
        Alert live = assertInstanceOf(AlertMessage.class, receiver.read()).alert();
        assertEquals(accepted.alertId(), live.id());
        assertEquals(new History(List.of(live)), history(receiver, 1));
        assertEquals(new History(List.of(live)), history(admin, 1));
        assertTrue(new AcknowledgementRepository(database).findByAlertId(live.id()).isEmpty());
        admin.send(new LoginResult(true)); error(admin.read(), "INVALID_MESSAGE"); // No queued ACK_STATUS.
        receiver.send(new Ack(live.id())); assertEquals(new AckResult(live.id(), true), receiver.read());
        assertInstanceOf(AckStatus.class, admin.read());
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void validLargeUtf8HistoryIsRejectedWithExplanationAndSmallerRetryWorks(boolean admin) throws Exception {
        String emoji = "\uD83D\uDEA8";
        List<Alert> all = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            Alert alert = new Alert(new UUID(0, index).toString(), "2026-11-09T00:00:00Z",
                    emoji.repeat(120), emoji.repeat(4000), Category.GENERAL, Priority.LOW);
            assertDoesNotThrow(() -> codec.encode(new AlertMessage(alert))); alerts.insert(alert); all.add(alert);
        }
        assertThrows(ProtocolException.class, () -> codec.encode(new History(all)));
        Peer peer = authorized(admin);
        peer.send(new GetHistory(4));
        ErrorMessage error = error(peer.read(), "INVALID_MESSAGE");
        assertTrue(error.message().contains("65536")); assertTrue(error.message().contains("smaller limit"));
        History smaller = assertInstanceOf(History.class, history(peer, 3));
        assertEquals(all.subList(1, 4), smaller.alerts());
        assertTrue(codec.encode(smaller).length() < codec.encode(smaller).getBytes(StandardCharsets.UTF_8).length);
        assertTrue(codec.encode(smaller).getBytes(StandardCharsets.UTF_8).length <= JsonMessageCodec.MAX_FRAME_BYTES);
    }

    @Test void jsonEscapingCountsTowardTheFrameLimit() throws Exception {
        for (int index = 0; index < 3; index++) alerts.insert(alert(index, "2026-11-09T00:00:00Z", "<".repeat(4000)));
        Peer peer = authorized(true);
        peer.send(new GetHistory(3)); error(peer.read(), "INVALID_MESSAGE");
        assertEquals(2, assertInstanceOf(History.class, history(peer, 2)).alerts().size());
    }

    @Test void exactly65536BytesSucceedsAndOneMoreByteGetsAnErrorWithoutDisconnect() throws Exception {
        List<Alert> all = new ArrayList<>();
        for (int index = 0; index < 20; index++) {
            all.add(new Alert(new UUID(0, index).toString(), "2026-11-09T00:00:00Z", "\uD83D\uDEA8 <>&",
                    "x".repeat(3000), Category.GENERAL, Priority.LOW));
        }
        int remaining = JsonMessageCodec.MAX_FRAME_BYTES - codec.encode(new History(all)).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(remaining > 0 && remaining < 20_000);
        for (int index = 0; remaining > 0; index++) {
            Alert original = all.get(index); int added = Math.min(remaining, 1000);
            all.set(index, new Alert(original.id(), original.createdAt(), original.title(), original.message() + "x".repeat(added), original.category(), original.priority()));
            remaining -= added;
        }
        assertEquals(65_536, codec.encode(new History(all)).getBytes(StandardCharsets.UTF_8).length);
        for (Alert alert : all) alerts.insert(alert);
        Peer peer = authorized(true);
        assertEquals(new History(all), history(peer, 20)); // Shared reader also enforces the wire byte limit.
        sql("UPDATE alerts SET message = message || 'x' WHERE id = '" + all.get(19).id() + "'");
        peer.send(new GetHistory(20)); error(peer.read(), "INVALID_MESSAGE");
        assertEquals(1, assertInstanceOf(History.class, history(peer, 1)).alerts().size());
    }

    @Test void pipelinedHistoryRepliesKeepTheirRequestOrder() throws Exception {
        Alert first = alert(1, "2026-11-09T00:00:00Z", "First"), second = alert(2, "2026-11-09T00:00:01Z", "Second");
        alerts.insert(first); alerts.insert(second);
        Peer peer = authorized(true);
        peer.send(new GetHistory(1)); peer.send(new GetHistory(2));
        assertEquals(new History(List.of(second)), peer.read()); assertEquals(new History(List.of(first, second)), peer.read());
    }

    @Test void directServiceCallCannotBypassLimitValidation() {
        HistoryService service = new HistoryService(alerts);
        for (int limit : List.of(0, 101)) assertThrows(ProtocolException.class, () -> service.retrieve(new GetHistory(limit)));
    }

    private Alert alert(int id, String time, String body) {
        return new Alert(new UUID(0, id).toString(), time, "Test", body, Category.GENERAL, Priority.LOW);
    }
    private ProtocolMessage history(Peer peer, int limit) throws IOException { peer.send(new GetHistory(limit)); return peer.read(); }
    private ErrorMessage error(ProtocolMessage message, String code) {
        ErrorMessage error = assertInstanceOf(ErrorMessage.class, message); assertEquals(code, error.code()); return error;
    }
    private void sql(String sql) throws Exception {
        database.transaction(connection -> { try (var statement = connection.prepareStatement(sql)) { statement.executeUpdate(); } return null; });
    }
    private Peer authorized(boolean admin) throws Exception {
        Peer peer = connect();
        if (admin) { peer.send(new Login("test-admin", "test-password")); assertEquals(new LoginResult(true), peer.read()); }
        else { peer.id = UUID.randomUUID().toString(); peer.send(new RegisterClient(peer.id, "History receiver")); assertEquals(new Registered(peer.id), peer.read()); }
        return peer;
    }
    private Peer connect() throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()); socket.setSoTimeout(3000);
        Socket server = listener.accept();
        Future<?> task = workers.submit(new ClientHandler(server, registry, credentials, new AlertCreationService(alerts, registry),
                new ClientRepository(database), new AcknowledgementService(alerts, new AcknowledgementRepository(database), registry), new HistoryService(alerts),
                new AcknowledgementQueryService(alerts, new AcknowledgementRepository(database)), () -> {}));
        Peer peer = new Peer(socket, server, task); peers.add(peer); return peer;
    }
    private final class Peer {
        final Socket socket, server;
        final Future<?> task;
        final BufferedInputStream input;
        final OutputStream output;
        String id;
        Peer(Socket socket, Socket server, Future<?> task) throws IOException {
            this.socket = socket; this.server = server; this.task = task;
            input = new BufferedInputStream(socket.getInputStream()); output = socket.getOutputStream();
        }
        void send(ProtocolMessage message) throws IOException { codec.write(output, message); }
        ProtocolMessage read() throws IOException { ProtocolMessage next; do { next = codec.read(input); } while (next instanceof ClientList); return next; }
        void raw(String json) throws IOException { output.write((json + "\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }
    }
}
