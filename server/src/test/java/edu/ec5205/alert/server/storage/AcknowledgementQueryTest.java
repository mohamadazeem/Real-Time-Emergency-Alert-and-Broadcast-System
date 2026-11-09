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

/** Socket queries exercise the real repositories, frame limits, and admin session isolation. */
@Timeout(30)
class AcknowledgementQueryTest {
    private static final String ALERT = "abcdef00-0000-0000-0000-000000000001";
    private static final String OTHER_ALERT = "abcdef00-0000-0000-0000-000000000002";
    private static final String TIME = "2026-11-09T00:00:00Z";
    @TempDir Path directory;
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final List<Peer> peers = new ArrayList<>();
    private final AdminCredentials credentials = AdminCredentials.fromEnvironment(Map.of(
            "EMERGENCY_ADMIN_USERNAME", "test-admin", "EMERGENCY_ADMIN_PASSWORD", "test-password"));
    private SqliteDatabase database;
    private AlertRepository alerts;
    private AcknowledgementRepository acks;
    private ServerSocket listener;
    private ExecutorService workers;

    @BeforeEach void start() throws Exception {
        database = new SqliteDatabase(directory.resolve("ack-query.db"));
        alerts = new AlertRepository(database); acks = new AcknowledgementRepository(database);
        alerts.insert(new Alert(ALERT, TIME, "Test", "Body", Category.GENERAL, Priority.LOW));
        alerts.insert(new Alert(OTHER_ALERT, TIME, "Other", "Body", Category.GENERAL, Priority.LOW));
        listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        workers = Executors.newFixedThreadPool(8);
    }

    @AfterEach void stop() throws Exception {
        for (Peer peer : peers) { peer.socket.close(); peer.server.close(); }
        listener.close(); workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test void adminGetsEmptyListForAnExistingAlertAndOtherCommandsRemainAvailable() throws Exception {
        Peer admin = login();
        assertEquals(new AckList(ALERT, List.of()), query(admin, ALERT));
        assertInstanceOf(History.class, exchange(admin, new GetHistory(1)));
        error(exchange(admin, new LoginResult(true)), "INVALID_MESSAGE");
    }

    @Test void unregisteredFailedLoginAndReceiverAreUnauthorizedEvenWhileAnotherAdminIsLoggedIn() throws Exception {
        Peer admin = login(), stranger = connect(), failed = connect(), receiver = receiver();
        failed.send(new Login("test-admin", "incorrect")); assertEquals(new LoginResult(false), failed.read());
        for (Peer peer : List.of(stranger, failed, receiver)) {
            error(query(peer, ALERT), "UNAUTHORIZED");
            if (peer != receiver) { assertNull(peer.read()); peer.task.get(3, TimeUnit.SECONDS); }
        }
        assertInstanceOf(History.class, exchange(receiver, new GetHistory(1)));
        assertSame(receiver.server, registry.find(receiver.id));
        assertEquals(new AckList(ALERT, List.of()), query(admin, ALERT));
    }

    @Test void invalidIdOnUnauthorizedRoleStillRequiresAdminAuthorization() throws Exception {
        Peer stranger = connect(), receiver = receiver();
        for (Peer peer : List.of(stranger, receiver)) {
            peer.raw("{\"version\":1,\"type\":\"GET_ACKS\",\"alertId\":42}");
            error(peer.read(), "UNAUTHORIZED");
        }
        assertNull(stranger.read());
        assertInstanceOf(History.class, exchange(receiver, new GetHistory(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"invalid-id\"", "\"1-1-1-1-1\"", "null", "42", "true", "missing"})
    void invalidAlertIdReturnsInvalidMessageAndClosesOnlyItsAdmin(String value) throws Exception {
        Peer invalid = login(), healthy = login();
        invalid.raw("{\"version\":1,\"type\":\"GET_ACKS\"" +
                (value.equals("missing") ? "" : ",\"alertId\":" + value) + "}");
        error(invalid.read(), "INVALID_MESSAGE"); assertNull(invalid.read()); invalid.task.get(3, TimeUnit.SECONDS);
        assertEquals(new AckList(ALERT, List.of()), query(healthy, ALERT));
    }

    @Test void unknownAlertIsDistinctFromExistingUnacknowledgedAlertAndAdminCanRetry() throws Exception {
        Peer admin = login();
        error(query(admin, UUID.randomUUID().toString()), "UNKNOWN_ALERT");
        assertEquals(new AckList(ALERT, List.of()), query(admin, ALERT));
    }

    @Test void sortingUsesInstantsThenClientIdAndReturnsOriginalTimestampSpellings() throws Exception {
        Acknowledgement oldest = ack(4, TIME);
        Acknowledgement tieLow = ack(1, "2026-11-09T00:00:00.1Z");
        Acknowledgement tieHigh = ack(3, "2026-11-09T00:00:00.100000000+00:00");
        Acknowledgement newest = ack(2, "2026-11-09T00:00:00.100000001Z");
        seed(List.of(newest, tieHigh, oldest, tieLow));
        assertFalse(acks.save(ALERT, new Acknowledgement(tieLow.clientId(), "2026-11-10T00:00:00Z")));
        Peer admin = login();
        assertEquals(new AckList(ALERT, List.of(oldest, tieLow, tieHigh, newest)), query(admin, ALERT));
        assertEquals(new AckList(OTHER_ALERT, List.of()), query(admin, OTHER_ALERT));
    }

    @Test void caseVariantRequestEchoesRequestedAlertIdAndRetainsStoredClientSpelling() throws Exception {
        var stored = new Acknowledgement("ABCDEF00-0000-0000-0000-000000000099", TIME);
        seed(List.of(stored));
        assertEquals(new AckList(ALERT.toUpperCase(Locale.ROOT), List.of(stored)), query(login(), ALERT.toUpperCase(Locale.ROOT)));
    }

    @Test void adminReconnectAndReopenedDatabaseRecoverAcknowledgementsMissedWhileOffline() throws Exception {
        Peer departed = login(), receiver = receiver();
        departed.socket.close(); departed.task.get(3, TimeUnit.SECONDS);
        assertEquals(new AckResult(ALERT, true), exchange(receiver, new Ack(ALERT)));
        var stored = acks.findByAlertId(ALERT); assertEquals(1, stored.size());
        receiver.socket.close(); receiver.task.get(3, TimeUnit.SECONDS);
        database = new SqliteDatabase(database.path()); alerts = new AlertRepository(database); acks = new AcknowledgementRepository(database);
        Peer reconnected = login();
        assertEquals(new AckList(ALERT, stored), query(reconnected, ALERT));
        assertEquals(new AckList(ALERT, stored), query(reconnected, ALERT));
        error(exchange(reconnected, new LoginResult(true)), "INVALID_MESSAGE"); // No replayed/new ACK_STATUS.
    }

    @Test void repeatedQueriesNeverCreateRowsOrNotifyOtherAdmins() throws Exception {
        var stored = ack(1, "2026-11-09T00:00:00.123456789+00:00"); seed(List.of(stored));
        Peer first = login(), observer = login();
        for (int index = 0; index < 3; index++) assertEquals(new AckList(ALERT, List.of(stored)), query(first, ALERT));
        assertEquals(List.of(stored), acks.findByAlertId(ALERT)); assertTrue(acks.findByAlertId(OTHER_ALERT).isEmpty());
        error(exchange(observer, new LoginResult(true)), "INVALID_MESSAGE");
        error(exchange(first, new LoginResult(true)), "INVALID_MESSAGE");
    }

    @ParameterizedTest @ValueSource(strings = {"alerts", "acknowledgements"})
    void lookupAndListDatabaseFailuresReturnGenericErrorNeverUnknownOrEmptyAndAllowRecovery(String table) throws Exception {
        var stored = ack(1, TIME); seed(List.of(stored)); Peer admin = login();
        sql("ALTER TABLE " + table + " RENAME TO private_ack_query_details");
        ErrorMessage failure = error(query(admin, ALERT), "INTERNAL_ERROR");
        assertEquals("Unable to retrieve acknowledgements.", failure.message());
        assertFalse(codec.encode(failure).contains("private_ack_query_details"));
        assertFalse(codec.encode(failure).contains(database.path().toString()));
        sql("ALTER TABLE private_ack_query_details RENAME TO " + table);
        assertEquals(new AckList(ALERT, List.of(stored)), query(admin, ALERT));
    }

    @Test void invalidStoredTimestampFailsGenericallyBeforeItCanReachWriter() throws Exception {
        seed(List.of(ack(1, TIME)));
        sql("UPDATE acknowledgements SET acknowledgedAt = 'private-invalid-value'");
        ErrorMessage failure = error(query(login(), ALERT), "INTERNAL_ERROR");
        assertEquals("Unable to retrieve acknowledgements.", failure.message());
    }

    @ParameterizedTest @ValueSource(ints = {65_535, 65_536, 65_537})
    void exactSerializedByteBoundarySendsWholeListOrExplicitErrorWithoutDisconnect(int targetBytes) throws Exception {
        List<Acknowledgement> stored = sizedList(targetBytes); seed(stored);
        AckList expected = new AckList(ALERT, stored);
        if (targetBytes <= JsonMessageCodec.MAX_FRAME_BYTES) {
            assertEquals(targetBytes, codec.encode(expected).getBytes(StandardCharsets.UTF_8).length);
        } else assertThrows(ProtocolException.class, () -> codec.encode(expected));
        Peer admin = login();
        ProtocolMessage reply = query(admin, ALERT);
        if (targetBytes <= JsonMessageCodec.MAX_FRAME_BYTES) assertEquals(expected, reply);
        else {
            ErrorMessage failure = error(reply, "INVALID_MESSAGE");
            assertTrue(failure.message().contains("65536")); assertTrue(failure.message().contains("no pagination"));
        }
        assertEquals(new AckList(OTHER_ALERT, List.of()), query(admin, OTHER_ALERT));
        assertEquals(stored, acks.findByAlertId(ALERT));
    }

    @Test void moreRowsThanAllocationBoundNeverProduceTruncatedSuccess() throws Exception {
        List<Acknowledgement> stored = new ArrayList<>();
        for (int index = 0; index < 1900; index++) stored.add(ack(index, TIME));
        seed(stored);
        assertEquals(stored.subList(0, 3), acks.findByAlertId(ALERT, 3));
        assertThrows(IllegalArgumentException.class, () -> acks.findByAlertId(ALERT, 0));
        Peer admin = login(); error(query(admin, ALERT), "INVALID_MESSAGE");
        assertEquals(new AckList(OTHER_ALERT, List.of()), query(admin, OTHER_ALERT));
        assertEquals(1900, acks.findByAlertId(ALERT).size());
    }

    @Test void pipelinedQueriesKeepResponseOrder() throws Exception {
        seed(List.of(ack(1, TIME))); Peer admin = login();
        admin.send(new GetAcks(ALERT)); admin.send(new GetAcks(OTHER_ALERT));
        assertEquals(new AckList(ALERT, List.of(ack(1, TIME))), admin.read());
        assertEquals(new AckList(OTHER_ALERT, List.of()), admin.read());
    }

    @Test void directServiceCallsAlsoValidateAlertId() {
        var service = new AcknowledgementQueryService(alerts, acks);
        assertThrows(ProtocolException.class, () -> service.retrieve(new GetAcks("invalid")));
    }

    private List<Acknowledgement> sizedList(int target) throws Exception {
        List<Acknowledgement> result = new ArrayList<>();
        int baseSize = codec.encode(new AckList(ALERT, List.of())).getBytes(StandardCharsets.UTF_8).length;
        int rowSize = codec.encode(new AckList(ALERT, List.of(ack(0, TIME)))).getBytes(StandardCharsets.UTF_8).length - baseSize;
        int count = (target - baseSize) / (rowSize + 1) - 1;
        for (int index = 0; index < count; index++) result.add(ack(index, TIME));
        int remaining = target - codec.encode(new AckList(ALERT, result)).getBytes(StandardCharsets.UTF_8).length;
        for (int index = 0; remaining > 0; index++) {
            int added = Math.min(10, remaining);
            if (remaining - added == 1) added--;
            assertTrue(added >= 2);
            result.set(index, ack(index, "2026-11-09T00:00:00." + "0".repeat(added - 1) + "Z"));
            remaining -= added;
        }
        return result;
    }

    private Acknowledgement ack(int id, String time) { return new Acknowledgement(new UUID(0, id).toString(), time); }
    private void seed(List<Acknowledgement> stored) throws Exception {
        database.transaction(connection -> {
            try (var clients = connection.prepareStatement("INSERT INTO clients(clientId, name) VALUES (?, ?)");
                 var insert = connection.prepareStatement("INSERT INTO acknowledgements(alertId, clientId, acknowledgedAt, acknowledgedAtSort) VALUES (?, ?, ?, ?)")) {
                for (var row : stored) {
                    clients.setString(1, row.clientId()); clients.setString(2, "Fixture"); clients.addBatch();
                    insert.setString(1, ALERT); insert.setString(2, row.clientId()); insert.setString(3, row.acknowledgedAt());
                    insert.setString(4, StorageValues.timeKey(row.acknowledgedAt())); insert.addBatch();
                }
                clients.executeBatch(); insert.executeBatch();
            }
            return null;
        });
    }
    private void sql(String command) throws Exception {
        database.transaction(connection -> { try (var statement = connection.prepareStatement(command)) { statement.executeUpdate(); } return null; });
    }
    private ProtocolMessage query(Peer peer, String alertId) throws IOException { return exchange(peer, new GetAcks(alertId)); }
    private ProtocolMessage exchange(Peer peer, ProtocolMessage request) throws IOException { peer.send(request); return peer.read(); }
    private ErrorMessage error(ProtocolMessage message, String code) {
        var error = assertInstanceOf(ErrorMessage.class, message); assertEquals(code, error.code()); return error;
    }
    private Peer login() throws Exception {
        Peer peer = connect(); assertEquals(new LoginResult(true), exchange(peer, new Login("test-admin", "test-password"))); return peer;
    }
    private Peer receiver() throws Exception {
        Peer peer = connect(); peer.id = UUID.randomUUID().toString();
        assertEquals(new Registered(peer.id), exchange(peer, new RegisterClient(peer.id, "Receiver"))); return peer;
    }
    private Peer connect() throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()); socket.setSoTimeout(3000);
        Socket server = listener.accept();
        Future<?> task = workers.submit(new ClientHandler(server, registry, credentials, new AlertCreationService(alerts, registry),
                new ClientRepository(database), new AcknowledgementService(alerts, acks, registry), new HistoryService(alerts),
                new AcknowledgementQueryService(alerts, acks), () -> {}));
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
