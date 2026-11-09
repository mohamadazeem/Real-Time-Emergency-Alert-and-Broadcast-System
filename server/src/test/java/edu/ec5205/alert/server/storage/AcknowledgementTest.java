package edu.ec5205.alert.server.storage;

import edu.ec5205.alert.common.*;
import edu.ec5205.alert.common.Messages.*;
import edu.ec5205.alert.server.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real sockets and real SQLite failures exercise commit-before-success, not mocked repositories. */
@Timeout(20)
class AcknowledgementTest {
    @TempDir Path directory;
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final List<Peer> peers = new ArrayList<>();
    private final AdminCredentials credentials = AdminCredentials.fromEnvironment(Map.of(
            "EMERGENCY_ADMIN_USERNAME", "test-admin", "EMERGENCY_ADMIN_PASSWORD", "test-password"));
    private SqliteDatabase database;
    private AlertRepository alerts;
    private ClientRepository clients;
    private AcknowledgementRepository acks;
    private AcknowledgementService service;
    private AlertCreationService creation;
    private ServerSocket listener;
    private ExecutorService workers;
    private String alertId;

    @BeforeEach void start() throws Exception {
        database = new SqliteDatabase(directory.resolve("ack.db"));
        alerts = new AlertRepository(database);
        clients = new ClientRepository(database);
        acks = new AcknowledgementRepository(database);
        service = new AcknowledgementService(alerts, acks, registry);
        creation = new AlertCreationService(alerts, registry);
        alertId = UUID.randomUUID().toString();
        alerts.insert(new Alert(alertId, Instant.now().toString(), "Test", "Body", Category.GENERAL, Priority.LOW));
        listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        workers = Executors.newFixedThreadPool(8);
    }

    @AfterEach void stop() throws Exception {
        for (Peer peer : peers) { peer.socket.close(); peer.server.close(); }
        listener.close(); workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test void successIsCommittedBeforeReplyAndNotifiesAllAuthenticatedAdminsOnly() throws Exception {
        Peer first = login(), second = login(), stranger = connect(), failed = connect();
        failed.send(new Login("test-admin", "wrong"));
        assertEquals(new LoginResult(false), failed.read());
        Peer receiver = receiver(UUID.randomUUID().toString(), "Receiver");
        Instant before = Instant.now();
        receiver.send(new Ack(alertId));
        assertEquals(new AckResult(alertId, true), receiver.read());
        var saved = new AcknowledgementRepository(new SqliteDatabase(database.path())).findByAlertId(alertId);
        assertEquals(1, saved.size());
        assertEquals(receiver.id, saved.get(0).clientId());
        Instant time = Instant.parse(saved.get(0).acknowledgedAt());
        assertFalse(time.isBefore(before)); assertFalse(time.isAfter(Instant.now()));
        AckStatus expected = new AckStatus(alertId, receiver.id, saved.get(0).acknowledgedAt());
        assertEquals(expected, first.read()); assertEquals(expected, second.read());
        quiet(stranger); quiet(failed); quiet(receiver);
    }

    @Test void repeatsAndCaseVariantsKeepFirstTimestampWithoutExtraNotifications() throws Exception {
        Peer admin = login(), receiver = receiver(UUID.randomUUID().toString(), "Receiver");
        receiver.send(new Ack(alertId)); assertEquals(new AckResult(alertId, true), receiver.read());
        AckStatus first = assertInstanceOf(AckStatus.class, admin.read());
        for (int i = 0; i < 5; i++) {
            receiver.send(new Ack(alertId.toUpperCase(Locale.ROOT)));
            assertEquals(new AckResult(alertId.toUpperCase(Locale.ROOT), true), receiver.read());
        }
        assertEquals(List.of(new Acknowledgement(receiver.id, first.acknowledgedAt())), acks.findByAlertId(alertId));
        quiet(admin);
    }

    @Test void concurrentDuplicateCallsHaveOneRecordAndOneNotification() throws Exception {
        Peer admin = login(), receiver = receiver(UUID.randomUUID().toString(), "Receiver");
        ExecutorService racers = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                // Two service instances prove that the database, not an application lock, deduplicates.
                var target = i % 2 == 0 ? service : new AcknowledgementService(alerts, acks, registry);
                results.add(racers.submit(() -> { go.await(); return target.acknowledge(alertId, receiver.id); }));
            }
            go.countDown();
            for (var result : results) assertTrue(result.get(5, TimeUnit.SECONDS));
            var saved = acks.findByAlertId(alertId);
            assertEquals(1, saved.size());
            assertEquals(new AckStatus(alertId, receiver.id, saved.get(0).acknowledgedAt()), admin.read());
            quiet(admin);
        } finally { go.countDown(); racers.shutdownNow(); assertTrue(racers.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void concurrentReceiversCannotClaimEachOthersIdentity() throws Exception {
        Peer admin = login();
        Peer first = receiver(UUID.randomUUID().toString(), "First");
        Peer second = receiver(UUID.randomUUID().toString(), "Second");
        first.raw("{\"version\":1,\"type\":\"ACK\",\"alertId\":\"" + alertId + "\",\"clientId\":\"" + second.id + "\",\"acknowledgedAt\":\"2000-01-01T00:00:00Z\"}");
        second.send(new Ack(alertId));
        assertEquals(new AckResult(alertId, true), first.read());
        assertEquals(new AckResult(alertId, true), second.read());
        var saved = acks.findByAlertId(alertId);
        assertEquals(Set.of(first.id, second.id), new HashSet<>(saved.stream().map(Acknowledgement::clientId).toList()));
        assertTrue(saved.stream().noneMatch(a -> a.acknowledgedAt().equals("2000-01-01T00:00:00Z")));
        Set<String> notified = new HashSet<>();
        notified.add(assertInstanceOf(AckStatus.class, admin.read()).clientId());
        notified.add(assertInstanceOf(AckStatus.class, admin.read()).clientId());
        assertEquals(Set.of(first.id, second.id), notified);
    }

    @Test void unknownAlertReturnsErrorWithoutStorageOrNotificationAndReceiverCanRetry() throws Exception {
        Peer admin = login(), receiver = receiver(UUID.randomUUID().toString(), "Receiver");
        receiver.send(new Ack(UUID.randomUUID().toString())); error(receiver.read(), "UNKNOWN_ALERT");
        assertTrue(acks.findByAlertId(alertId).isEmpty()); quiet(admin);
        receiver.send(new Ack(alertId)); assertEquals(new AckResult(alertId, true), receiver.read());
        assertInstanceOf(AckStatus.class, admin.read());
    }

    @Test void unregisteredFailedLoginAndAdminConnectionsCannotAcknowledge() throws Exception {
        Peer admin = login(), stranger = connect(), failed = connect();
        failed.send(new Login("test-admin", "wrong")); assertEquals(new LoginResult(false), failed.read());
        for (Peer peer : List.of(stranger, failed, admin)) {
            peer.send(new Ack(alertId)); error(peer.read(), "UNAUTHORIZED");
            if (peer != admin) { assertNull(peer.read()); peer.task.get(3, TimeUnit.SECONDS); }
        }
        admin.send(new GetAcks(alertId)); assertEquals(new AckList(alertId, List.of()), admin.read());
        assertTrue(acks.findByAlertId(alertId).isEmpty());
    }

    @Test void recognizedInvalidAckFromUnauthorizedRolesStillRequiresReceiverAuthorization() throws Exception {
        Peer admin = login(), stranger = connect();
        for (Peer peer : List.of(admin, stranger)) {
            peer.raw("{\"version\":1,\"type\":\"ACK\",\"alertId\":42}");
            error(peer.read(), "UNAUTHORIZED");
        }
        assertNull(stranger.read()); stranger.task.get(3, TimeUnit.SECONDS);
        admin.send(new LoginResult(true)); error(admin.read(), "INVALID_MESSAGE");
        assertTrue(acks.findByAlertId(alertId).isEmpty());
    }

    @Test void malformedAckClosesOnlyItsReceiverAndDoesNotPersist() throws Exception {
        Peer admin = login(), invalid = receiver(UUID.randomUUID().toString(), "Invalid");
        Peer healthy = receiver(UUID.randomUUID().toString(), "Healthy");
        invalid.raw("{\"version\":1,\"type\":\"ACK\",\"alertId\":42}");
        error(invalid.read(), "INVALID_MESSAGE"); assertNull(invalid.read());
        invalid.task.get(3, TimeUnit.SECONDS); assertNull(registry.find(invalid.id));
        assertSame(healthy.server, registry.find(healthy.id));
        assertTrue(acks.findByAlertId(alertId).isEmpty()); quiet(admin);
    }

    @Test void sqlFailureNeverSendsSuccessOrNotificationAndRetryAfterRecoveryWorks() throws Exception {
        Peer admin = login(), receiver = receiver(UUID.randomUUID().toString(), "Receiver");
        sql("CREATE TRIGGER reject_ack BEFORE INSERT ON acknowledgements BEGIN SELECT RAISE(ABORT, 'private-details'); END");
        receiver.send(new Ack(alertId));
        ErrorMessage error = error(receiver.read(), "INTERNAL_ERROR");
        assertEquals("Unable to store the acknowledgement.", error.message());
        assertFalse(codec.encode(error).contains("private-details"));
        assertTrue(acks.findByAlertId(alertId).isEmpty()); quiet(admin); quiet(receiver);
        sql("DROP TRIGGER reject_ack");
        receiver.send(new Ack(alertId)); assertEquals(new AckResult(alertId, true), receiver.read());
        assertInstanceOf(AckStatus.class, admin.read());
    }

    @Test void lookupStorageFailureIsInternalErrorRatherThanUnknownAlert() throws Exception {
        Peer admin = login(), receiver = receiver(UUID.randomUUID().toString(), "Receiver");
        sql("ALTER TABLE alerts RENAME TO unavailable_alerts");
        receiver.send(new Ack(alertId)); error(receiver.read(), "INTERNAL_ERROR"); quiet(admin); quiet(receiver);
    }

    @Test void registrationCommitsBeforeRegisteredAndDisconnectPreservesPersistentClientAndAck() throws Exception {
        String id = UUID.randomUUID().toString();
        Peer receiver = receiver(id, "First name");
        var reopened = new SqliteDatabase(database.path());
        assertEquals(new ClientInfo(id, "First name"), new ClientRepository(reopened).findById(id).orElseThrow());
        receiver.send(new Ack(alertId)); assertEquals(new AckResult(alertId, true), receiver.read());
        var original = acks.findByAlertId(alertId);
        receiver.socket.close(); receiver.task.get(3, TimeUnit.SECONDS); assertNull(registry.find(id));
        Peer replacement = receiver(id.toUpperCase(Locale.ROOT), "New name");
        assertEquals("New name", clients.findById(id).orElseThrow().name());
        replacement.send(new Ack(alertId)); assertEquals(new AckResult(alertId, true), replacement.read());
        assertEquals(original, new AcknowledgementRepository(new SqliteDatabase(database.path())).findByAlertId(alertId));
    }

    @Test void registrationFailureReleasesReservationWithoutPublishingSuccessAndAllowsRetry() throws Exception {
        Peer peer = connect(); String id = UUID.randomUUID().toString();
        sql("CREATE TRIGGER reject_client BEFORE INSERT ON clients BEGIN SELECT RAISE(ABORT, 'private-details'); END");
        peer.send(new RegisterClient(id, "Receiver"));
        assertEquals("Unable to store the client registration.", error(peer.read(), "INTERNAL_ERROR").message());
        assertNull(registry.find(id)); assertTrue(clients.findById(id).isEmpty()); quiet(peer);
        sql("DROP TRIGGER reject_client");
        peer.send(new RegisterClient(id, "Retry")); assertEquals(new Registered(id), peer.read());
        assertEquals("Retry", clients.findById(id).orElseThrow().name());
        peer.socket.close(); peer.task.get(3, TimeUnit.SECONDS); assertNull(registry.find(id));
    }

    @Test void duplicateRegistrationDoesNotOverwritePersistedName() throws Exception {
        Peer owner = receiver(UUID.randomUUID().toString(), "Owner"); Peer duplicate = connect();
        duplicate.send(new RegisterClient(owner.id.toUpperCase(Locale.ROOT), "Intruder"));
        error(duplicate.read(), "DUPLICATE_CLIENT"); assertNull(duplicate.read());
        assertEquals("Owner", clients.findById(owner.id).orElseThrow().name());
        assertSame(owner.server, registry.find(owner.id));
    }

    @Test void disconnectedAdminDoesNotAffectStorageOrOtherAdminAndNewSocketIsUnauthenticated() throws Exception {
        Peer departed = login(), healthy = login(), receiver = receiver(UUID.randomUUID().toString(), "Receiver");
        departed.socket.close(); departed.task.get(3, TimeUnit.SECONDS);
        Peer stranger = connect();
        receiver.send(new Ack(alertId)); assertEquals(new AckResult(alertId, true), receiver.read());
        assertInstanceOf(AckStatus.class, healthy.read()); quiet(stranger);
        assertEquals(1, acks.findByAlertId(alertId).size());
    }

    private void sql(String command) throws Exception {
        database.transaction(connection -> { try (var statement = connection.prepareStatement(command)) { statement.executeUpdate(); } return null; });
    }
    private void quiet(Peer peer) throws Exception {
        peer.socket.setSoTimeout(120);
        try { assertThrows(SocketTimeoutException.class, peer::read); } finally { peer.socket.setSoTimeout(3000); }
    }
    private ErrorMessage error(ProtocolMessage reply, String code) {
        var error = assertInstanceOf(ErrorMessage.class, reply); assertEquals(code, error.code()); return error;
    }
    private Peer receiver(String id, String name) throws Exception {
        Peer peer = connect(); peer.id = id;
        peer.send(new RegisterClient(id, name)); assertEquals(new Registered(id), peer.read()); return peer;
    }
    private Peer login() throws Exception {
        Peer peer = connect(); peer.send(new Login("test-admin", "test-password"));
        assertEquals(new LoginResult(true), peer.read()); return peer;
    }
    private Peer connect() throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()); socket.setSoTimeout(3000);
        Socket server = listener.accept();
        Future<?> task = workers.submit(new ClientHandler(server, registry, credentials, creation, clients, service, new HistoryService(alerts), new AcknowledgementQueryService(alerts, acks), () -> {}));
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
