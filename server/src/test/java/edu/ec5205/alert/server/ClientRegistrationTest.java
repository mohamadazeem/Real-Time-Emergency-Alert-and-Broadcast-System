package edu.ec5205.alert.server;

import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.*;
import edu.ec5205.alert.common.Priority;
import edu.ec5205.alert.common.ProtocolMessage;
import edu.ec5205.alert.server.storage.AlertRepository;
import edu.ec5205.alert.server.storage.SqliteDatabase;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback sockets exercise framing, handler lifetime, and registry ownership. */
@Timeout(15)
class ClientRegistrationTest {
    private static final String ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_ID = "223e4567-e89b-12d3-a456-426614174000";
    private final JsonMessageCodec codec = new JsonMessageCodec();
    private final ConnectionRegistry registry = new ConnectionRegistry();
    private final AdminCredentials credentials = AdminCredentials.fromEnvironment(Map.of(
            "EMERGENCY_ADMIN_USERNAME", "test-admin", "EMERGENCY_ADMIN_PASSWORD", "test-password"));
    private final List<Peer> peers = new ArrayList<>();
    private ServerSocket listener;
    private ExecutorService workers;
    @TempDir Path directory;
    private AlertRepository alerts;
    private AlertCreationService alertCreation;

    @BeforeEach
    void start() throws Exception {
        alerts = new AlertRepository(new SqliteDatabase(directory.resolve("registration-test.db")));
        alertCreation = new AlertCreationService(alerts, registry);
        listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        workers = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(4), new ThreadPoolExecutor.AbortPolicy());
    }

    @AfterEach
    void stop() throws Exception {
        for (Peer peer : peers) {
            peer.socket.close();
            peer.serverSocket.close();
        }
        listener.close();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "Workers must not remain blocked in reads");
    }

    @Test
    void registersWithExactSharedReplyAndKeepsConnectionOpen() throws Exception {
        Peer peer = connect();
        peer.send(new RegisterClient(ID, "Library PC සිංහල"));
        assertEquals(new Registered(ID), peer.read());
        assertSame(peer.serverSocket, registry.find(ID));
        assertFalse(peer.task.isDone(), "Handler must remain available after REGISTERED");

        peer.send(new GetHistory(1));
        assertError(peer.read(), "INVALID_MESSAGE"); // History is a future milestone.
        assertSame(peer.serverSocket, registry.find(ID));
        assertFalse(peer.task.isDone());
    }

    @Test
    void duplicateClosesOnlyNewcomerAndPreservesOriginalRegistration() throws Exception {
        Peer owner = register(ID);
        Peer duplicate = connect();
        duplicate.send(new RegisterClient(ID.toUpperCase(), "Duplicate"));
        assertError(duplicate.read(), "DUPLICATE_CLIENT");
        assertNull(duplicate.read());
        duplicate.task.get(3, TimeUnit.SECONDS);
        assertSame(owner.serverSocket, registry.find(ID));
        owner.send(new GetHistory(1));
        assertError(owner.read(), "INVALID_MESSAGE");
    }

    @Test
    void simultaneousRegistrationHasExactlyOneWinner() throws Exception {
        Peer first = connect();
        Peer second = connect();
        first.send(new RegisterClient(ID, "First"));
        second.send(new RegisterClient(ID, "Second"));
        ProtocolMessage firstReply = first.read();
        ProtocolMessage secondReply = second.read();
        assertTrue((firstReply instanceof Registered) ^ (secondReply instanceof Registered));
        Peer winner = firstReply instanceof Registered ? first : second;
        Peer loser = winner == first ? second : first;
        assertError(winner == first ? secondReply : firstReply, "DUPLICATE_CLIENT");
        assertNull(loser.read());
        loser.task.get(3, TimeUnit.SECONDS);
        assertSame(winner.serverSocket, registry.find(ID));
    }

    @Test
    void disconnectRemovesRegistrationAndAllowsReregistration() throws Exception {
        Peer first = register(ID);
        first.socket.close();
        first.task.get(3, TimeUnit.SECONDS);
        assertNull(registry.find(ID));

        Peer replacement = register(ID);
        assertSame(replacement.serverSocket, registry.find(ID));
    }

    @Test
    void staleCleanupCannotRemoveReplacementOwner() throws Exception {
        try (ClientConnection oldSocket = new ClientConnection(new Socket(), 32, registry::unregister);
             ClientConnection replacement = new ClientConnection(new Socket(), 32, registry::unregister)) {
            assertTrue(registry.register(ID, oldSocket));
            registry.unregister(ID, oldSocket);
            assertTrue(registry.register(ID, replacement));
            registry.unregister(ID, oldSocket); // Simulate delayed old cleanup.
            assertSame(replacement.socket(), registry.find(ID));
            registry.unregister(ID, replacement);
            assertNull(registry.find(ID));
        }
    }

    @Test
    void registryInsertionIsAtomicUnderRacingCallers() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        try (ClientConnection first = new ClientConnection(new Socket(), 32, registry::unregister);
             ClientConnection second = new ClientConnection(new Socket(), 32, registry::unregister)) {
            Future<Boolean> firstResult = racers.submit(() -> {
                ready.countDown();
                go.await();
                return registry.register(ID, first);
            });
            Future<Boolean> secondResult = racers.submit(() -> {
                ready.countDown();
                go.await();
                return registry.register(ID, second);
            });
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            go.countDown();
            boolean firstWon = firstResult.get(3, TimeUnit.SECONDS);
            boolean secondWon = secondResult.get(3, TimeUnit.SECONDS);
            assertTrue(firstWon ^ secondWon);
            assertSame(firstWon ? first.socket() : second.socket(), registry.find(ID));
        } finally {
            go.countDown();
            racers.shutdownNow();
            assertTrue(racers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void oneSocketCannotChangeItsRegisteredIdentity() throws Exception {
        Peer peer = register(ID);
        peer.send(new RegisterClient(OTHER_ID, "Changed identity"));
        assertError(peer.read(), "INVALID_MESSAGE");
        assertSame(peer.serverSocket, registry.find(ID));
        assertNull(registry.find(OTHER_ID));
        peer.send(new RegisterClient(ID, "Again"));
        assertError(peer.read(), "INVALID_MESSAGE");
        assertSame(peer.serverSocket, registry.find(ID));
    }

    @Test
    void unregisteredReceiverRequestIsUnauthorizedAndConnectionCloses() throws Exception {
        Peer peer = connect();
        peer.send(new GetHistory(1));
        assertError(peer.read(), "UNAUTHORIZED");
        assertNull(peer.read());
        peer.task.get(3, TimeUnit.SECONDS);
        assertNull(registry.find(ID));
    }

    @Test
    void adminRequestsAreUnauthorizedEvenOnRegisteredReceiver() throws Exception {
        Peer peer = register(ID);
        peer.send(new CreateAlert(OTHER_ID, "Title", "Body", Category.GENERAL, Priority.LOW));
        ErrorMessage error = assertError(peer.read(), "UNAUTHORIZED");
        assertEquals(OTHER_ID, error.requestId());
        peer.send(new GetClients());
        assertError(peer.read(), "UNAUTHORIZED");
        peer.send(new GetAcks(OTHER_ID));
        assertError(peer.read(), "UNAUTHORIZED");
        assertSame(peer.serverSocket, registry.find(ID));
    }

    @Test
    void invalidLoginDoesNotAuthenticateAndCanBeRetried() throws Exception {
        Peer peer = connect();
        peer.send(new Login("test-admin", "wrong-password"));
        assertEquals(new LoginResult(false), peer.read());
        peer.send(new Login("test-admin", "test-password"));
        assertEquals(new LoginResult(true), peer.read());
        assertFalse(peer.task.isDone());
    }

    @Test
    void malformedJsonClosesAndRemovesOnlyThatConnectionsRegistration() throws Exception {
        Peer invalid = register(ID);
        Peer healthy = register(OTHER_ID);
        invalid.raw("not JSON\n");
        assertError(invalid.read(), "INVALID_MESSAGE");
        assertNull(invalid.read());
        invalid.task.get(3, TimeUnit.SECONDS);
        assertNull(registry.find(ID));
        assertSame(healthy.serverSocket, registry.find(OTHER_ID));
        healthy.send(new GetHistory(1));
        assertError(healthy.read(), "INVALID_MESSAGE");
    }

    @Test
    void invalidSchemaEchoesRecoverableRequestId() throws Exception {
        Peer peer = connect();
        peer.raw("{\"version\":1,\"type\":\"CREATE_ALERT\",\"requestId\":\"" + OTHER_ID + "\"}\n");
        assertEquals(OTHER_ID, assertError(peer.read(), "UNAUTHORIZED").requestId());
        assertNull(peer.read());
    }

    @Test
    void unsupportedVersionOrTypeProducesDocumentedError() throws Exception {
        for (String json : List.of("{\"version\":2,\"type\":\"GET_CLIENTS\"}",
                "{\"version\":1,\"type\":\"UNKNOWN_TYPE\"}")) {
            Peer peer = connect();
            peer.raw(json + "\n");
            assertError(peer.read(), "INVALID_MESSAGE");
            assertNull(peer.read());
            peer.task.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void earlyDisconnectAndTruncatedFrameDoNotLeakRegistration() throws Exception {
        Peer early = connect();
        early.socket.close();
        early.task.get(3, TimeUnit.SECONDS);
        Peer truncated = register(ID);
        truncated.raw("{\"version\":1");
        truncated.socket.shutdownOutput();
        assertError(truncated.read(), "INVALID_MESSAGE");
        assertNull(truncated.read());
        truncated.task.get(3, TimeUnit.SECONDS);
        assertNull(registry.find(ID));
        register(ID);
    }

    @Test
    void closingServerSocketReleasesReadAndRemovesRegistration() throws Exception {
        Peer peer = register(ID);
        peer.serverSocket.close(); // Same operation used by shutdown cleanup.
        peer.task.get(3, TimeUnit.SECONDS);
        assertNull(registry.find(ID));
        assertNull(peer.read());
    }

    @Test
    void authenticatedAdminStaysOpenAndGetsAvailabilityErrorsRatherThanUnauthorized() throws Exception {
        Peer admin = login();
        admin.send(new CreateAlert(OTHER_ID, "Title", "Body", Category.GENERAL, Priority.LOW));
        AlertAccepted accepted = assertInstanceOf(AlertAccepted.class, admin.read());
        assertEquals(OTHER_ID, accepted.requestId());
        assertTrue(alerts.findById(accepted.alertId()).isPresent());
        for (ProtocolMessage request : List.of(new GetClients(), new GetAcks(ID), new GetHistory(1))) {
            admin.send(request);
            assertTrue(assertError(admin.read(), "INVALID_MESSAGE").message().contains("not implemented"));
        }
        admin.send(new Ack(ID));
        assertError(admin.read(), "UNAUTHORIZED"); // ACK belongs to receivers.
        assertFalse(admin.task.isDone());
        assertNull(registry.find(ID));
    }

    @Test
    void wrongUsernameDoesNotAuthorizeCommands() throws Exception {
        Peer peer = connect();
        peer.send(new Login("wrong-user", "test-password"));
        assertEquals(new LoginResult(false), peer.read());
        peer.send(new GetClients());
        assertError(peer.read(), "UNAUTHORIZED");
        assertNull(peer.read());
    }

    @Test
    void receiverCannotBecomeAdminEvenWithCorrectCredentials() throws Exception {
        Peer receiver = register(ID);
        receiver.send(new Login("test-admin", "test-password"));
        assertError(receiver.read(), "INVALID_MESSAGE");
        receiver.send(new GetClients());
        assertError(receiver.read(), "UNAUTHORIZED");
        assertSame(receiver.serverSocket, registry.find(ID));
        receiver.socket.close();
        receiver.task.get(3, TimeUnit.SECONDS);
        assertNull(registry.find(ID));
        register(ID);
    }

    @Test
    void adminCannotRegisterOrChangeItsRoleByLoggingInAgain() throws Exception {
        Peer admin = login();
        admin.send(new RegisterClient(ID, "Disallowed receiver"));
        assertError(admin.read(), "INVALID_MESSAGE");
        assertNull(registry.find(ID));
        admin.send(new Login("wrong-user", "wrong-password"));
        assertError(admin.read(), "INVALID_MESSAGE");
        admin.send(new GetClients());
        assertError(admin.read(), "INVALID_MESSAGE"); // Still an admin, not downgraded.
        assertFalse(admin.task.isDone());
    }

    @Test
    void adminAuthenticationIsIsolatedFromOtherConnections() throws Exception {
        Peer admin = login();
        Peer stranger = connect();
        stranger.send(new GetClients());
        assertError(stranger.read(), "UNAUTHORIZED");
        assertNull(stranger.read());
        stranger.task.get(3, TimeUnit.SECONDS);
        Peer receiver = register(ID);
        receiver.send(new GetClients());
        assertError(receiver.read(), "UNAUTHORIZED");
        admin.send(new GetClients());
        assertError(admin.read(), "INVALID_MESSAGE");
        assertSame(receiver.serverSocket, registry.find(ID));
    }

    @Test
    void failedLoginLeavesConnectionEligibleForReceiverRegistration() throws Exception {
        Peer peer = connect();
        peer.send(new Login("test-admin", "wrong-password"));
        assertEquals(new LoginResult(false), peer.read());
        peer.send(new RegisterClient(ID, "Receiver after failed login"));
        assertEquals(new Registered(ID), peer.read());
        assertSame(peer.serverSocket, registry.find(ID));
    }

    @Test
    void newConnectionDoesNotInheritDisconnectedAdminsRole() throws Exception {
        Peer admin = login();
        admin.socket.close();
        admin.task.get(3, TimeUnit.SECONDS);
        Peer newcomer = connect();
        newcomer.send(new GetClients());
        assertError(newcomer.read(), "UNAUTHORIZED");
        assertNull(newcomer.read());
    }

    private Peer login() throws Exception {
        Peer peer = connect();
        peer.send(new Login("test-admin", "test-password"));
        assertEquals(new LoginResult(true), peer.read());
        return peer;
    }

    private Peer register(String id) throws Exception {
        Peer peer = connect();
        peer.send(new RegisterClient(id, "Library PC"));
        assertEquals(new Registered(id), peer.read());
        return peer;
    }

    private Peer connect() throws IOException {
        Socket socket = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        socket.setSoTimeout(3000);
        Socket serverSocket = listener.accept();
        Future<?> task = workers.submit(new ClientHandler(serverSocket, registry, credentials, alertCreation, () -> {}));
        Peer peer = new Peer(socket, serverSocket, task);
        peers.add(peer);
        return peer;
    }

    private static ErrorMessage assertError(ProtocolMessage reply, String code) {
        ErrorMessage error = assertInstanceOf(ErrorMessage.class, reply);
        assertEquals(code, error.code());
        assertFalse(error.message().isBlank());
        return error;
    }

    private final class Peer {
        final Socket socket;
        final Socket serverSocket;
        final Future<?> task;
        final BufferedInputStream input;
        final OutputStream output;

        Peer(Socket socket, Socket serverSocket, Future<?> task) throws IOException {
            this.socket = socket;
            this.serverSocket = serverSocket;
            this.task = task;
            this.input = new BufferedInputStream(socket.getInputStream());
            this.output = socket.getOutputStream();
        }

        void send(ProtocolMessage message) throws IOException { codec.write(output, message); }
        ProtocolMessage read() throws IOException { return codec.read(input); }
        void raw(String text) throws IOException {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }
}
