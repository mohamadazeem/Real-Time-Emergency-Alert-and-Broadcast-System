package edu.ec5205.alert.server;

import edu.ec5205.alert.common.*;
import edu.ec5205.alert.common.Messages.*;
import edu.ec5205.alert.server.storage.*;
import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

/** These tests read every frame, including unsolicited membership updates. */
@Timeout(30)
class ClientListTest {
    private static final String HIGH = "ABCDEF00-0000-0000-0000-000000000002";
    private static final String LOW = "12345600-0000-0000-0000-000000000001";
    @TempDir Path directory;
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final List<Peer> peers = new ArrayList<>();
    private final AdminCredentials credentials = AdminCredentials.fromEnvironment(Map.of(
            "EMERGENCY_ADMIN_USERNAME", "test-admin", "EMERGENCY_ADMIN_PASSWORD", "test-password"));
    private SqliteDatabase database;
    private ClientRepository clients;
    private ServerSocket listener;
    private ExecutorService workers;

    @BeforeEach void start() throws Exception {
        database = new SqliteDatabase(directory.resolve("clients.db")); clients = new ClientRepository(database);
        listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress()); workers = Executors.newFixedThreadPool(8);
    }
    @AfterEach void stop() throws Exception {
        for (Peer peer : peers) { peer.socket.close(); peer.server.close(); }
        listener.close(); workers.shutdownNow(); assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test void emptyLiveListExcludesHistoricalClientsAdminsAndUnregisteredSockets() throws Exception {
        clients.save(new ClientInfo(HIGH, "Historical, offline"));
        Peer admin = login(); login(); connect();
        assertEquals(new ClientList(List.of()), exchange(admin, new GetClients()));
        assertTrue(clients.findById(HIGH).isPresent());
    }

    @Test void onlyAuthenticatedAdminsMayQueryAndReceiversStayRegisteredAfterRejection() throws Exception {
        Peer admin = login(), stranger = connect(), failed = connect();
        assertEquals(new LoginResult(false), exchange(failed, new Login("test-admin", "wrong")));
        Peer receiver = register(LOW, "Receiver"); assertEquals(list(LOW, "Receiver"), admin.read());
        for (Peer peer : List.of(stranger, failed, receiver)) {
            error(exchange(peer, new GetClients()), "UNAUTHORIZED");
            if (peer != receiver) { assertNull(peer.read()); peer.task.get(3, TimeUnit.SECONDS); }
        }
        assertEquals(new History(List.of()), exchange(receiver, new GetHistory(1)));
        assertSame(receiver.server, registry.find(LOW));
        assertEquals(list(LOW, "Receiver"), exchange(admin, new GetClients()));
    }

    @Test void allAdminsReceiveOrderedNamesOnJoinLeaveAndRenameOnReconnect() throws Exception {
        Peer first = login(), second = login();
        Peer high = register(HIGH, "caf\u00e9 \uD83D\uDEA8 <>&");
        ClientInfo highInfo = new ClientInfo(HIGH, "caf\u00e9 \uD83D\uDEA8 <>&");
        assertEquals(new ClientList(List.of(highInfo)), first.read()); assertEquals(new ClientList(List.of(highInfo)), second.read());
        Peer low = register(LOW, "Library");
        ClientList both = new ClientList(List.of(new ClientInfo(LOW, "Library"), highInfo));
        assertEquals(both, first.read()); assertEquals(both, second.read());
        assertEquals(both, exchange(first, new GetClients()));
        high.socket.close(); high.task.get(3, TimeUnit.SECONDS);
        assertEquals(list(LOW, "Library"), first.read()); assertEquals(list(LOW, "Library"), second.read());
        assertTrue(clients.findById(HIGH).isPresent()); // The database is not the live list.
        Peer replacement = register(HIGH.toLowerCase(Locale.ROOT), "Renamed");
        ClientList renamed = new ClientList(List.of(new ClientInfo(LOW, "Library"), new ClientInfo(HIGH.toLowerCase(Locale.ROOT), "Renamed")));
        assertEquals(renamed, first.read()); assertEquals(renamed, second.read());
        low.socket.close(); replacement.socket.close(); low.task.get(3, TimeUnit.SECONDS); replacement.task.get(3, TimeUnit.SECONDS);
        first.read(); assertEquals(new ClientList(List.of()), first.read());
        second.read(); assertEquals(new ClientList(List.of()), second.read());
    }

    @Test void duplicateRegistrationDoesNotChangeNameOrEmitMembershipUpdate() throws Exception {
        Peer admin = login(), owner = register(HIGH, "Owner"); assertEquals(list(HIGH, "Owner"), admin.read());
        Peer duplicate = connect(); error(exchange(duplicate, new RegisterClient(HIGH.toLowerCase(Locale.ROOT), "Intruder")), "DUPLICATE_CLIENT");
        assertNull(duplicate.read()); duplicate.task.get(3, TimeUnit.SECONDS);
        quiet(admin); assertSame(owner.server, registry.find(HIGH));
        assertEquals(list(HIGH, "Owner"), exchange(admin, new GetClients()));
        assertEquals("Owner", clients.findById(HIGH).orElseThrow().name());
    }

    @Test void registeredIsQueuedBeforeAdminCanObserveTheNewMember() throws Exception {
        Peer admin = login(), receiver = connect();
        receiver.send(new RegisterClient(LOW, "Receiver"));
        assertEquals(list(LOW, "Receiver"), admin.read());
        receiver.send(new GetHistory(1));
        assertEquals(new Registered(LOW), receiver.read());
        assertEquals(new History(List.of()), receiver.read());
    }

    @Test void failedRegistrationPersistenceDoesNotPublishAndRetryEmitsOneJoin() throws Exception {
        Peer admin = login(), receiver = connect();
        registrationSql("CREATE TRIGGER reject_registration BEFORE INSERT ON clients BEGIN SELECT RAISE(ABORT, 'private-details'); END");
        error(exchange(receiver, new RegisterClient(LOW, "Receiver")), "INTERNAL_ERROR");
        quiet(admin); assertNull(registry.find(LOW)); assertTrue(clients.findById(LOW).isEmpty());
        assertEquals(new ClientList(List.of()), exchange(admin, new GetClients()));
        registrationSql("DROP TRIGGER reject_registration");
        assertEquals(new Registered(LOW), exchange(receiver, new RegisterClient(LOW, "Receiver")));
        assertEquals(list(LOW, "Receiver"), admin.read()); quiet(admin);
    }

    @Test void loginRacingMembershipChangeAlwaysQueuesLoginResultBeforeAnyClientList() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 20; index++) {
                ConnectionRegistry registry = new ConnectionRegistry(); ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (ClientConnection admin = new ClientConnection(new OutputSocket(output), 8, registry::unregister);
                     ClientConnection receiver = new ClientConnection(new Socket(), 4, registry::unregister)) {
                    CountDownLatch go = new CountDownLatch(1);
                    Future<?> login = racers.submit(() -> { go.await(); registry.authenticateAdmin(admin); return null; });
                    Future<?> join = racers.submit(() -> { go.await(); assertTrue(registry.register(new ClientInfo(LOW, "Receiver"), receiver)); return null; });
                    go.countDown(); login.get(3, TimeUnit.SECONDS); join.get(3, TimeUnit.SECONDS);
                    registry.sendClientList(admin); admin.startWriter(); admin.finish();
                    ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
                    assertEquals(new LoginResult(true), codec.read(frames));
                    assertEquals(list(LOW, "Receiver"), codec.read(frames));
                    ProtocolMessage optional = codec.read(frames);
                    if (optional != null) assertEquals(list(LOW, "Receiver"), optional);
                    assertNull(codec.read(frames));
                }
            }
        } finally { racers.shutdownNow(); assertTrue(racers.awaitTermination(3, TimeUnit.SECONDS)); }
    }

    @Test void disconnectedAdminGetsNoUpdatesAndNewSocketMustAuthenticateAgain() throws Exception {
        Peer departed = login(); departed.socket.close(); departed.task.get(3, TimeUnit.SECONDS);
        Peer healthy = login(), stranger = connect(); register(LOW, "Receiver");
        assertEquals(list(LOW, "Receiver"), healthy.read()); quiet(stranger);
        error(exchange(stranger, new GetClients()), "UNAUTHORIZED"); assertNull(stranger.read());
    }

    @Test void invalidReceiverFrameRemovesOnlyItsMembershipAndPublishesCleanup() throws Exception {
        Peer admin = login(), receiver = register(LOW, "Receiver"); assertEquals(list(LOW, "Receiver"), admin.read());
        receiver.raw("broken JSON"); error(receiver.read(), "INVALID_MESSAGE"); assertNull(receiver.read());
        receiver.task.get(3, TimeUnit.SECONDS); assertEquals(new ClientList(List.of()), admin.read());
        assertEquals(new ClientList(List.of()), exchange(admin, new GetClients()));
    }

    @ParameterizedTest @ValueSource(ints = {65_536, 65_537})
    void exactSerializedClientListFrameLimitIsCheckedForPushAndQuery(int target) throws Exception {
        int overhead = codec.encode(list(LOW, "")).getBytes(StandardCharsets.UTF_8).length;
        String name = "x".repeat(target - overhead);
        assertDoesNotThrow(() -> codec.encode(new RegisterClient(LOW, name)));
        Peer admin = login(), receiver = register(LOW, name);
        ProtocolMessage update = admin.read();
        if (target == 65_536) {
            assertEquals(list(LOW, name), update);
            assertEquals(65_536, codec.encode(update).getBytes(StandardCharsets.UTF_8).length);
            assertEquals(update, exchange(admin, new GetClients()));
        } else {
            assertTrue(error(update, "INVALID_MESSAGE").message().contains("65536"));
            error(exchange(admin, new GetClients()), "INVALID_MESSAGE");
        }
        receiver.socket.close(); receiver.task.get(3, TimeUnit.SECONDS);
        assertEquals(new ClientList(List.of()), admin.read());
        assertEquals(new ClientList(List.of()), exchange(admin, new GetClients()));
    }

    @Test void aggregateUtf8NamesCanOverflowAndShrinkBackToValidWithoutDisconnectingAdmin() throws Exception {
        String name = "\uD83D\uDEA8".repeat(9000); Peer admin = login();
        Peer first = register(LOW, name); assertEquals(list(LOW, name), admin.read());
        register(HIGH, name); ErrorMessage failure = error(admin.read(), "INVALID_MESSAGE");
        assertTrue(failure.message().contains("no pagination")); error(exchange(admin, new GetClients()), "INVALID_MESSAGE");
        first.socket.close(); first.task.get(3, TimeUnit.SECONDS);
        assertEquals(list(HIGH, name), admin.read()); assertEquals(list(HIGH, name), exchange(admin, new GetClients()));
    }

    @Test void concurrentPublicationQueriesAndRemovalNeverRegressSnapshotOrder() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ExecutorService racers = Executors.newFixedThreadPool(8);
        List<ClientConnection> receivers = new ArrayList<>();
        int count = 40;
        try (ClientConnection admin = new ClientConnection(new OutputSocket(output), 256, registry::unregister)) {
            registry.authenticateAdmin(admin);
            for (int index = 0; index < count; index++) receivers.add(new ClientConnection(new Socket(), 4, registry::unregister));
            List<Future<?>> tasks = new ArrayList<>();
            CountDownLatch go = new CountDownLatch(1);
            for (int index = 0; index < count; index++) {
                final int id = index;
                tasks.add(racers.submit(() -> {
                    go.await(); assertTrue(registry.register(new ClientInfo(new UUID(0, id).toString(), "Receiver"), receivers.get(id)));
                    registry.sendClientList(admin); return null;
                }));
            }
            go.countDown(); for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            registry.sendClientList(admin);
            tasks.clear();
            for (ClientConnection receiver : receivers) tasks.add(racers.submit(() -> { registry.unregister(receiver); registry.sendClientList(admin); }));
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            registry.sendClientList(admin); admin.startWriter(); admin.finish();
            ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
            assertEquals(new LoginResult(true), codec.read(frames));
            Set<String> previous = Set.of();
            for (int index = 0; index < 2 * count + 1; index++) {
                Set<String> current = ids(assertInstanceOf(ClientList.class, codec.read(frames)));
                assertTrue(current.containsAll(previous), "An older snapshot overtook a newer registration"); previous = current;
            }
            assertEquals(count, previous.size());
            for (int index = 0; index < 2 * count + 1; index++) {
                Set<String> current = ids(assertInstanceOf(ClientList.class, codec.read(frames)));
                assertTrue(previous.containsAll(current), "An older snapshot overtook a newer removal"); previous = current;
            }
            assertTrue(previous.isEmpty()); assertNull(codec.read(frames));
        } finally {
            receivers.forEach(ClientConnection::close); racers.shutdownNow(); assertTrue(racers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void slowAdminOverflowDoesNotBlockRegistrationOrOtherAdminsAndReceiverQueueFailureIsCleanedUp() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry(); BlockingOutput blocked = new BlockingOutput();
        ByteArrayOutputStream healthy = new ByteArrayOutputStream();
        try (ClientConnection slow = new ClientConnection(new OutputSocket(blocked), 1, registry::unregister);
             ClientConnection fast = new ClientConnection(new OutputSocket(healthy), 32, registry::unregister);
             ClientConnection first = new ClientConnection(new Socket(), 1, registry::unregister);
             ClientConnection second = new ClientConnection(new Socket(), 4, registry::unregister)) {
            registry.authenticateAdmin(slow); slow.startWriter(); assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
            registry.authenticateAdmin(fast);
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertTrue(registry.register(new ClientInfo(LOW, "First"), first)));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> assertTrue(registry.register(new ClientInfo(HIGH, "Second"), second)));
            assertTrue(slow.isClosed()); assertTrue(slow.awaitWriter(3000)); assertFalse(fast.isClosed());
            // REGISTERED fills the first receiver's mailbox. Overflow must remove it and publish only its departure.
            assertFalse(first.send(new History(List.of()))); assertNull(registry.find(LOW));
            registry.sendClientList(fast); fast.startWriter(); fast.finish();
            ByteArrayInputStream frames = new ByteArrayInputStream(healthy.toByteArray());
            assertEquals(new LoginResult(true), codec.read(frames)); assertEquals(list(LOW, "First"), codec.read(frames));
            assertEquals(new ClientList(List.of(new ClientInfo(LOW, "First"), new ClientInfo(HIGH, "Second"))), codec.read(frames));
            assertEquals(list(HIGH, "Second"), codec.read(frames)); assertEquals(list(HIGH, "Second"), codec.read(frames));
            assertNull(codec.read(frames)); assertSame(second.socket(), registry.find(HIGH));
        }
    }

    @Test void failedRegisteredOfferNeverPublishesAndStaleCleanupCannotRemoveReplacement() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry(); ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ClientConnection admin = new ClientConnection(new OutputSocket(output), 32, registry::unregister);
             ClientConnection rejected = new ClientConnection(new Socket(), 1, registry::unregister);
             ClientConnection old = new ClientConnection(new Socket(), 4, registry::unregister);
             ClientConnection replacement = new ClientConnection(new Socket(), 4, registry::unregister)) {
            registry.authenticateAdmin(admin); rejected.send(new History(List.of()));
            assertFalse(registry.register(new ClientInfo(LOW, "Rejected"), rejected));
            assertTrue(registry.register(new ClientInfo(LOW, "Old"), old)); registry.unregister(old);
            assertTrue(registry.register(new ClientInfo(LOW, "New"), replacement));
            registry.unregister(LOW, old); registry.sendClientList(admin); admin.startWriter(); admin.finish();
            ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
            assertEquals(new LoginResult(true), codec.read(frames)); assertEquals(list(LOW, "Old"), codec.read(frames));
            assertEquals(new ClientList(List.of()), codec.read(frames)); assertEquals(list(LOW, "New"), codec.read(frames));
            assertEquals(list(LOW, "New"), codec.read(frames)); assertNull(codec.read(frames));
        }
    }

    @Test void receiverWriterFailurePublishesRemovalAndAdminWriterCleanupReleasesSubscription() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry(); ByteArrayOutputStream output = new ByteArrayOutputStream();
        OutputStream failure = new OutputStream() { @Override public void write(int value) throws IOException { throw new IOException("Broken"); } };
        try (ClientConnection admin = new ClientConnection(new OutputSocket(output), 16, registry::unregister);
             ClientConnection receiver = new ClientConnection(new OutputSocket(failure), 4, registry::unregister)) {
            registry.authenticateAdmin(admin); assertTrue(registry.register(new ClientInfo(LOW, "Receiver"), receiver));
            receiver.startWriter(); assertTrue(receiver.awaitWriter(3000)); assertNull(registry.find(LOW));
            admin.startWriter(); admin.finish(); assertTrue(admin.awaitWriter(100));
            ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
            assertEquals(new LoginResult(true), codec.read(frames)); assertEquals(list(LOW, "Receiver"), codec.read(frames));
            assertEquals(new ClientList(List.of()), codec.read(frames)); assertNull(codec.read(frames));
        }
    }

    private Set<String> ids(ClientList list) {
        List<String> ids = list.clients().stream().map(ClientInfo::clientId).toList();
        assertEquals(ids.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(), ids); return new HashSet<>(ids);
    }
    private void registrationSql(String sql) throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + database.path());
             var statement = connection.prepareStatement(sql)) { statement.executeUpdate(); }
    }
    private ClientList list(String id, String name) { return new ClientList(List.of(new ClientInfo(id, name))); }
    private void quiet(Peer peer) throws Exception {
        peer.socket.setSoTimeout(120); try { assertThrows(SocketTimeoutException.class, peer::read); } finally { peer.socket.setSoTimeout(3000); }
    }
    private ProtocolMessage exchange(Peer peer, ProtocolMessage message) throws IOException { peer.send(message); return peer.read(); }
    private ErrorMessage error(ProtocolMessage message, String code) { var error = assertInstanceOf(ErrorMessage.class, message); assertEquals(code, error.code()); return error; }
    private Peer register(String id, String name) throws Exception { Peer peer = connect(); assertEquals(new Registered(id), exchange(peer, new RegisterClient(id, name))); return peer; }
    private Peer login() throws Exception { Peer peer = connect(); assertEquals(new LoginResult(true), exchange(peer, new Login("test-admin", "test-password"))); return peer; }
    private Peer connect() throws Exception {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort()); socket.setSoTimeout(3000); Socket server = listener.accept();
        AlertRepository alerts = new AlertRepository(database); AcknowledgementRepository acks = new AcknowledgementRepository(database);
        Future<?> task = workers.submit(new ClientHandler(server, registry, credentials, new AlertCreationService(alerts, registry), clients,
                new AcknowledgementService(alerts, acks, registry), new HistoryService(alerts), new AcknowledgementQueryService(alerts, acks), () -> {}));
        Peer peer = new Peer(socket, server, task); peers.add(peer); return peer;
    }
    private final class Peer {
        final Socket socket, server; final Future<?> task; final BufferedInputStream input; final OutputStream output;
        Peer(Socket socket, Socket server, Future<?> task) throws IOException {
            this.socket = socket; this.server = server; this.task = task; input = new BufferedInputStream(socket.getInputStream()); output = socket.getOutputStream();
        }
        void send(ProtocolMessage message) throws IOException { codec.write(output, message); }
        ProtocolMessage read() throws IOException { return codec.read(input); }
        void raw(String json) throws IOException { output.write((json + "\n").getBytes(StandardCharsets.UTF_8)); output.flush(); }
    }
    private static final class OutputSocket extends Socket {
        private final OutputStream output;
        OutputSocket(OutputStream output) { this.output = output; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public synchronized void close() throws IOException { output.close(); super.close(); }
    }
    private static final class BlockingOutput extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        @Override public void write(int value) throws IOException {
            entered.countDown(); try { released.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new IOException("Closed blocked writer");
        }
        @Override public void close() { released.countDown(); }
    }
}
