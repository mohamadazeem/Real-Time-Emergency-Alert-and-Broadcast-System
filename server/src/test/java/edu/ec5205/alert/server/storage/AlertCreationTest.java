package edu.ec5205.alert.server.storage;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.*;
import edu.ec5205.alert.common.Priority;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import edu.ec5205.alert.server.AdminCredentials;
import edu.ec5205.alert.server.AcknowledgementService;
import edu.ec5205.alert.server.HistoryService;
import edu.ec5205.alert.server.AcknowledgementQueryService;
import edu.ec5205.alert.server.AlertCreationService;
import edu.ec5205.alert.server.ClientHandler;
import edu.ec5205.alert.server.ConnectionRegistry;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class AlertCreationTest {
    private static final String REQUEST_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String CLIENT_ID = "223e4567-e89b-12d3-a456-426614174000";
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final AdminCredentials credentials = AdminCredentials.fromEnvironment(Map.of(
            "EMERGENCY_ADMIN_USERNAME", "test-admin", "EMERGENCY_ADMIN_PASSWORD", "test-password"));
    private final List<Peer> peers = new ArrayList<>();
    @TempDir Path directory;
    private Path path;
    private SqliteDatabase database;
    private AlertRepository alerts;
    private AlertCreationService service;
    private ServerSocket listener;
    private ExecutorService workers;

    @BeforeEach
    void start() throws Exception {
        path = directory.resolve("alert-creation.db");
        database = new SqliteDatabase(path);
        alerts = new AlertRepository(database);
        service = new AlertCreationService(alerts, registry);
        listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());
    }

    @AfterEach
    void stop() throws Exception {
        for (Peer peer : peers) { peer.socket.close(); peer.serverSocket.close(); }
        listener.close();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void adminRequestCommitsBeforeAcceptanceAndSurvivesReopeningWithServerGeneratedFields() throws Exception {
        Peer admin = login();
        CreateAlert request = new CreateAlert(REQUEST_ID, "අනතුරු café 🚨", "Evacuate.\nUse exit 'A'.", Category.FIRE, Priority.CRITICAL);
        JsonObject raw = JsonParser.parseString(codec.encode(request)).getAsJsonObject();
        raw.addProperty("id", CLIENT_ID); // Extra caller-supplied fields must not become server-owned fields.
        raw.addProperty("alertId", CLIENT_ID);
        raw.addProperty("createdAt", "2000-01-01T00:00:00Z");
        Instant before = Instant.now();
        admin.raw(raw.toString());
        AlertAccepted accepted = assertInstanceOf(AlertAccepted.class, admin.read());
        Instant after = Instant.now();
        assertEquals(REQUEST_ID, accepted.requestId());
        assertEquals(4, UUID.fromString(accepted.alertId()).version());
        assertNotEquals(REQUEST_ID, accepted.alertId());
        assertNotEquals(CLIENT_ID, accepted.alertId());
        // A second database instance can see the row as soon as the reply arrives.
        Alert saved = new AlertRepository(new SqliteDatabase(path)).findById(accepted.alertId()).orElseThrow();
        assertEquals(request.title(), saved.title());
        assertEquals(request.message(), saved.message());
        assertEquals(request.category(), saved.category());
        assertEquals(request.priority(), saved.priority());
        assertTrue(saved.createdAt().endsWith("Z"));
        Instant createdAt = Instant.parse(saved.createdAt());
        assertFalse(createdAt.isBefore(before));
        assertFalse(createdAt.isAfter(after));
        admin.socket.close();
        admin.task.get(3, TimeUnit.SECONDS);
        assertEquals(saved, new AlertRepository(new SqliteDatabase(path)).findById(accepted.alertId()).orElseThrow());
    }

    @Test
    void unregisteredAndReceiverConnectionsCannotCreateEvenWhenAnotherConnectionIsAdmin() throws Exception {
        login(); // Authentication must not grant a role to other sockets.
        Peer stranger = connect();
        stranger.send(request(REQUEST_ID));
        assertEquals(REQUEST_ID, error(stranger.read(), "UNAUTHORIZED").requestId());
        assertNull(stranger.read());
        stranger.task.get(3, TimeUnit.SECONDS);
        Peer receiver = connect();
        receiver.send(new RegisterClient(CLIENT_ID, "Receiver"));
        assertEquals(new Registered(CLIENT_ID), receiver.read());
        receiver.send(request(REQUEST_ID));
        assertEquals(REQUEST_ID, error(receiver.read(), "UNAUTHORIZED").requestId());
        JsonObject invalid = JsonParser.parseString(codec.encode(request(REQUEST_ID))).getAsJsonObject();
        invalid.addProperty("title", " ");
        receiver.raw(invalid.toString());
        error(receiver.read(), "UNAUTHORIZED");
        assertSame(receiver.serverSocket, registry.find(CLIENT_ID));
        assertTrue(alerts.recent(100).isEmpty());
    }

    @Test
    void failedLoginCannotCreateAlerts() throws Exception {
        Peer peer = connect();
        peer.send(new Login("test-admin", "incorrect"));
        assertEquals(new LoginResult(false), peer.read());
        peer.send(request(REQUEST_ID));
        error(peer.read(), "UNAUTHORIZED");
        assertNull(peer.read());
        assertTrue(alerts.recent(100).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"empty-title", "blank-title", "long-title", "empty-message", "blank-message", "long-message",
            "category", "priority", "missing-title", "null-message", "number-title", "boolean-priority"})
    void invalidAlertPayloadsAreCorrelatedAndDoNotPersistOrCloseAdmin(String scenario) throws Exception {
        Peer admin = login();
        JsonObject raw = JsonParser.parseString(codec.encode(request(REQUEST_ID))).getAsJsonObject();
        switch (scenario) {
            case "empty-title" -> raw.addProperty("title", "");
            case "blank-title" -> raw.addProperty("title", " \t ");
            case "long-title" -> raw.addProperty("title", "x".repeat(121));
            case "empty-message" -> raw.addProperty("message", "");
            case "blank-message" -> raw.addProperty("message", "\t\n");
            case "long-message" -> raw.addProperty("message", "x".repeat(4001));
            case "category" -> raw.addProperty("category", "UNKNOWN");
            case "priority" -> raw.addProperty("priority", "HIGH");
            case "missing-title" -> raw.remove("title");
            case "null-message" -> raw.add("message", null);
            case "number-title" -> raw.addProperty("title", 42);
            case "boolean-priority" -> raw.addProperty("priority", true);
            default -> fail("Unknown scenario");
        }
        admin.raw(raw.toString());
        assertEquals(REQUEST_ID, error(admin.read(), "INVALID_ALERT").requestId());
        assertTrue(alerts.recent(100).isEmpty());
        admin.send(request(REQUEST_ID));
        assertInstanceOf(AlertAccepted.class, admin.read());
        assertEquals(1, alerts.recent(100).size());
    }

    @Test
    void invalidCorrelationOrMalformedJsonUsesInvalidMessageRatherThanInvalidAlert() throws Exception {
        for (String raw : List.of("{\"version\":1,\"type\":\"CREATE_ALERT\",\"requestId\":\"not-a-uuid\"}", "broken JSON")) {
            Peer admin = login();
            admin.raw(raw);
            assertNull(error(admin.read(), "INVALID_MESSAGE").requestId());
            assertNull(admin.read());
            admin.task.get(3, TimeUnit.SECONDS);
        }
        assertTrue(alerts.recent(100).isEmpty());
    }

    @Test
    void realSqlFailureReturnsInternalErrorWithoutAcceptanceOrInternalDetailsAndAllowsRecovery() throws Exception {
        Peer receiver = connect();
        receiver.send(new RegisterClient(CLIENT_ID, "Receiver"));
        assertEquals(new Registered(CLIENT_ID), receiver.read());
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("""
                    CREATE TRIGGER reject_alert BEFORE INSERT ON alerts BEGIN
                        SELECT RAISE(ABORT, 'private-sql-internals-and-path');
                    END
                    """)) { statement.executeUpdate(); }
            return null;
        });
        Peer admin = login();
        admin.send(request(REQUEST_ID));
        ErrorMessage failure = error(admin.read(), "INTERNAL_ERROR");
        assertEquals(REQUEST_ID, failure.requestId());
        assertEquals("Unable to store the alert.", failure.message());
        assertFalse(codec.encode(failure).contains("private-sql-internals-and-path"));
        assertFalse(codec.encode(failure).contains(path.toString()));
        assertTrue(alerts.recent(100).isEmpty());
        receiver.socket.setSoTimeout(150);
        assertThrows(SocketTimeoutException.class, receiver::read);
        receiver.socket.setSoTimeout(3000);
        database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DROP TRIGGER reject_alert")) { statement.executeUpdate(); }
            return null;
        });
        admin.send(request(REQUEST_ID));
        AlertAccepted accepted = assertInstanceOf(AlertAccepted.class, admin.read());
        assertTrue(alerts.findById(accepted.alertId()).isPresent());
        assertEquals(alerts.findById(accepted.alertId()).orElseThrow(), assertInstanceOf(AlertMessage.class, receiver.read()).alert());
        assertEquals(1, alerts.recent(100).size()); // No deferred/false acceptance or failed row.
    }

    @Test
    void textBoundariesAndAllDocumentedEnumsAreAccepted() throws Exception {
        Peer admin = login();
        for (Category category : Category.values()) {
            for (Priority priority : Priority.values()) {
                String requestId = UUID.randomUUID().toString();
                admin.send(new CreateAlert(requestId, "🚨".repeat(120), "x".repeat(4000), category, priority));
                AlertAccepted accepted = assertInstanceOf(AlertAccepted.class, admin.read());
                assertEquals(requestId, accepted.requestId());
                Alert saved = alerts.findById(accepted.alertId()).orElseThrow();
                assertEquals(category, saved.category());
                assertEquals(priority, saved.priority());
            }
        }
        assertEquals(12, alerts.recent(100).size());
    }

    @Test
    void concurrentAdminsReceiveDistinctIdsAndTheirOwnRequestCorrelation() throws Exception {
        Peer first = login();
        Peer second = login();
        first.send(request(REQUEST_ID));
        second.send(request(CLIENT_ID));
        AlertAccepted firstReply = assertInstanceOf(AlertAccepted.class, first.read());
        AlertAccepted secondReply = assertInstanceOf(AlertAccepted.class, second.read());
        assertEquals(REQUEST_ID, firstReply.requestId());
        assertEquals(CLIENT_ID, secondReply.requestId());
        assertNotEquals(firstReply.alertId(), secondReply.alertId());
        assertEquals(2, alerts.recent(100).size());
    }

    @Test
    void creationBroadcastsStoredAlertAndHistoryRetrievesItWhileUnknownAckIsRejected() throws Exception {
        Peer receiver = connect();
        receiver.send(new RegisterClient(CLIENT_ID, "Receiver"));
        assertEquals(new Registered(CLIENT_ID), receiver.read());
        Peer admin = login();
        admin.send(request(REQUEST_ID));
        AlertAccepted accepted = assertInstanceOf(AlertAccepted.class, admin.read());
        AlertMessage delivered = assertInstanceOf(AlertMessage.class, receiver.read());
        assertEquals(alerts.findById(accepted.alertId()).orElseThrow(), delivered.alert());
        receiver.send(new GetHistory(1));
        assertEquals(new History(List.of(delivered.alert())), receiver.read());
        receiver.send(new Ack(CLIENT_ID));
        error(receiver.read(), "UNKNOWN_ALERT");
    }

    @Test
    void serviceRevalidatesDirectlyConstructedRecordsBeforeWriting() {
        assertThrows(ProtocolException.class, () -> service.create(new CreateAlert(REQUEST_ID, " ", "Body", Category.FIRE, Priority.LOW)));
        assertDoesNotThrow(() -> assertTrue(alerts.recent(100).isEmpty()));
    }

    @Test
    void twoReceiversGetTheSameStoredAlertButAdminAndUnregisteredSocketsDoNot() throws Exception {
        Peer first = connect();
        first.send(new RegisterClient(CLIENT_ID, "First"));
        assertEquals(new Registered(CLIENT_ID), first.read());
        String secondId = UUID.randomUUID().toString();
        Peer second = connect();
        second.send(new RegisterClient(secondId, "Second"));
        assertEquals(new Registered(secondId), second.read());
        Peer admin = login();
        Peer unregistered = connect();
        admin.send(request(REQUEST_ID));
        AlertAccepted accepted = assertInstanceOf(AlertAccepted.class, admin.read());
        Alert stored = alerts.findById(accepted.alertId()).orElseThrow();
        assertEquals(new AlertMessage(stored), first.read());
        assertEquals(new AlertMessage(stored), second.read());
        admin.socket.setSoTimeout(150);
        unregistered.socket.setSoTimeout(150);
        assertThrows(SocketTimeoutException.class, admin::read);
        assertThrows(SocketTimeoutException.class, unregistered::read);
    }

    @Test
    void receiverDisconnectDoesNotPreventCreationOrDeliveryToAnotherReceiver() throws Exception {
        Peer departed = connect();
        departed.send(new RegisterClient(CLIENT_ID, "Departing"));
        assertEquals(new Registered(CLIENT_ID), departed.read());
        Peer healthy = connect();
        String healthyId = UUID.randomUUID().toString();
        healthy.send(new RegisterClient(healthyId, "Healthy"));
        assertEquals(new Registered(healthyId), healthy.read());
        Peer admin = login();
        departed.socket.close();
        departed.task.get(3, TimeUnit.SECONDS);
        assertNull(registry.find(CLIENT_ID));
        for (int index = 0; index < 2; index++) {
            admin.send(request(UUID.randomUUID().toString()));
            AlertAccepted accepted = assertInstanceOf(AlertAccepted.class, admin.read());
            assertEquals(alerts.findById(accepted.alertId()).orElseThrow(), assertInstanceOf(AlertMessage.class, healthy.read()).alert());
        }
        assertSame(healthy.serverSocket, registry.find(healthyId));
        assertEquals(2, alerts.recent(100).size());
    }

    private static CreateAlert request(String requestId) {
        return new CreateAlert(requestId, "Test alert", "Test message", Category.GENERAL, Priority.MEDIUM);
    }

    private Peer login() throws Exception {
        Peer peer = connect();
        peer.send(new Login("test-admin", "test-password"));
        assertEquals(new LoginResult(true), peer.read());
        return peer;
    }

    private Peer connect() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        socket.setSoTimeout(3000);
        Socket serverSocket = listener.accept();
        Future<?> task = workers.submit(new ClientHandler(serverSocket, registry, credentials, service,
                new ClientRepository(database), new AcknowledgementService(alerts, new AcknowledgementRepository(database), registry), new HistoryService(alerts),
                new AcknowledgementQueryService(alerts, new AcknowledgementRepository(database)), () -> {}));
        Peer peer = new Peer(socket, serverSocket, task);
        peers.add(peer);
        return peer;
    }

    private static ErrorMessage error(ProtocolMessage message, String code) {
        ErrorMessage error = assertInstanceOf(ErrorMessage.class, message);
        assertEquals(code, error.code());
        return error;
    }

    private final class Peer {
        final Socket socket;
        final Socket serverSocket;
        final Future<?> task;
        final BufferedInputStream input;
        final OutputStream output;
        Peer(Socket socket, Socket serverSocket, Future<?> task) throws IOException {
            this.socket = socket; this.serverSocket = serverSocket; this.task = task;
            input = new BufferedInputStream(socket.getInputStream()); output = socket.getOutputStream();
        }
        void send(ProtocolMessage message) throws IOException { codec.write(output, message); }
        ProtocolMessage read() throws IOException { ProtocolMessage next; do { next = codec.read(input); } while (next instanceof ClientList); return next; }
        void raw(String json) throws IOException { output.write((json + "\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }
    }
}
