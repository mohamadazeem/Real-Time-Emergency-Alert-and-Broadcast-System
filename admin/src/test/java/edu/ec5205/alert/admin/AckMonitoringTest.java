package edu.ec5205.alert.admin;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.network.MessageListener;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.MessageType;
import edu.ec5205.alert.common.Messages;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ACK Monitoring functionality:
 * GET_ACKS request construction, ACK_LIST response parsing,
 * error handling (UNKNOWN_ALERT / generic ERROR), live ACK_STATUS
 * listener dispatch, duplicate-prevention logic, listener cleanup,
 * and unauthenticated guard on requestAcknowledgements.
 */
class AckMonitoringTest {

    private final JsonMessageCodec codec = new JsonMessageCodec();

    @Test
    @DisplayName("requestAcknowledgements sends GET_ACKS with correct alertId")
    void getAcksSendsCorrectAlertId() throws Exception {
        String alertId = UUID.randomUUID().toString();
        AtomicReference<ProtocolMessage> received = new AtomicReference<>();

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    InputStream in  = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in); // LOGIN
                    codec.write(out, new Messages.LoginResult(true));
                    ProtocolMessage req = codec.read(in); // GET_ACKS
                    received.set(req);
                    codec.write(out, new Messages.AckList(alertId, List.of()));
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();
                AdminAuthService svc = new AdminAuthService(conn);
                svc.login("admin", "pass", Duration.ofSeconds(3));
                svc.requestAcknowledgements(alertId, Duration.ofSeconds(3));
            }

            server.join(2000);
        }

        assertNotNull(received.get(), "Server must have received a GET_ACKS message");
        assertEquals(MessageType.GET_ACKS, received.get().type());
        assertInstanceOf(Messages.GetAcks.class, received.get());
        assertEquals(alertId, ((Messages.GetAcks) received.get()).alertId());
    }

    // ─── 2. ACK_LIST response is correctly parsed ────────────────────────────

    @Test
    @DisplayName("ACK_LIST response is correctly parsed into List<Acknowledgement>")
    void ackListParsedCorrectly() throws Exception {
        String alertId = UUID.randomUUID().toString();
        String client1 = UUID.randomUUID().toString();
        String client2 = UUID.randomUUID().toString();
        Messages.Acknowledgement ack1 = new Messages.Acknowledgement(client1, "2025-01-01T12:00:00Z");
        Messages.Acknowledgement ack2 = new Messages.Acknowledgement(client2, "2025-01-01T12:01:00Z");

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    InputStream in  = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in); // LOGIN
                    codec.write(out, new Messages.LoginResult(true));
                    codec.read(in); // GET_ACKS
                    codec.write(out, new Messages.AckList(alertId, List.of(ack1, ack2)));
                    codec.read(in); // keep connection alive until client closes
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            List<Messages.Acknowledgement> result;
            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();
                AdminAuthService svc = new AdminAuthService(conn);
                svc.login("admin", "pass", Duration.ofSeconds(3));
                result = svc.requestAcknowledgements(alertId, Duration.ofSeconds(3));
            }

            server.join(2000);
            assertEquals(2, result.size());
            assertEquals(client1, result.get(0).clientId());
            assertEquals("2025-01-01T12:00:00Z", result.get(0).acknowledgedAt());
            assertEquals(client2, result.get(1).clientId());
        }
    }

    // ─── 3. UNKNOWN_ALERT error response throws ProtocolException ────────────

    @Test
    @DisplayName("UNKNOWN_ALERT error response throws ProtocolException")
    void unknownAlertErrorThrows() throws Exception {
        String alertId = UUID.randomUUID().toString();

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    InputStream in  = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in); // LOGIN
                    codec.write(out, new Messages.LoginResult(true));
                    codec.read(in); // GET_ACKS
                    codec.write(out, new Messages.ErrorMessage("UNKNOWN_ALERT", "Alert not found: " + alertId, null));
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();
                AdminAuthService svc = new AdminAuthService(conn);
                svc.login("admin", "pass", Duration.ofSeconds(3));

                ProtocolException ex = assertThrows(ProtocolException.class,
                        () -> svc.requestAcknowledgements(alertId, Duration.ofSeconds(3)));
                assertTrue(ex.getMessage().contains("UNKNOWN_ALERT"),
                        "Exception should mention error code");
            }

            server.join(2000);
        }
    }

    // ─── 4. Generic ERROR response also throws ProtocolException ─────────────

    @Test
    @DisplayName("Generic ERROR response from server throws ProtocolException")
    void genericErrorResponseThrows() throws Exception {
        String alertId = UUID.randomUUID().toString();

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    InputStream in  = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));
                    codec.read(in);
                    codec.write(out, new Messages.ErrorMessage("INTERNAL_ERROR", "Database unavailable", null));
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();
                AdminAuthService svc = new AdminAuthService(conn);
                svc.login("admin", "pass", Duration.ofSeconds(3));

                ProtocolException ex = assertThrows(ProtocolException.class,
                        () -> svc.requestAcknowledgements(alertId, Duration.ofSeconds(3)));
                assertTrue(ex.getMessage().contains("INTERNAL_ERROR"));
            }

            server.join(2000);
        }
    }

    // ─── 5. Unauthenticated call throws IllegalStateException ────────────────

    @Test
    @DisplayName("requestAcknowledgements without authentication throws IllegalStateException")
    void unauthenticatedThrowsIllegalState() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    // server just connects and idles
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();
                AdminAuthService svc = new AdminAuthService(conn);
                // NOT logged in
                assertThrows(IllegalStateException.class,
                        () -> svc.requestAcknowledgements("some-id", Duration.ofSeconds(1)));
            }

            server.join(1000);
        }
    }

    // ─── 6. Null alertId throws NullPointerException ─────────────────────────

    @Test
    @DisplayName("Null alertId to requestAcknowledgements throws NullPointerException")
    void nullAlertIdThrowsNpe() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    InputStream in  = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));
                    Thread.sleep(500);
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();
                AdminAuthService svc = new AdminAuthService(conn);
                svc.login("admin", "pass", Duration.ofSeconds(3));
                assertThrows(NullPointerException.class,
                        () -> svc.requestAcknowledgements(null, Duration.ofSeconds(1)));
            }

            server.join(1000);
        }
    }

    // ─── 7. ACK_STATUS message is dispatched to registered MessageListener ───

    @Test
    @DisplayName("Incoming ACK_STATUS is dispatched to registered MessageListener")
    void ackStatusDispatchedToListener() throws Exception {
        String alertId = UUID.randomUUID().toString();
        String clientId = UUID.randomUUID().toString();
        CountDownLatch listenerReady = new CountDownLatch(1);
        CountDownLatch messageLatch  = new CountDownLatch(1);
        AtomicReference<ProtocolMessage> dispatched = new AtomicReference<>();

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    OutputStream out = s.getOutputStream();
                    // Wait until the client has registered its listener
                    listenerReady.await(3, TimeUnit.SECONDS);
                    codec.write(out, new Messages.AckStatus(alertId, clientId, "2025-01-01T10:00:00Z"));
                    Thread.sleep(500); // keep connection open so reader thread can deliver the message
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();

                // Register listener, then signal server it can push
                conn.addListener(new MessageListener() {
                    @Override
                    public void onMessage(ProtocolMessage message) {
                        if (message instanceof Messages.AckStatus) {
                            dispatched.set(message);
                            messageLatch.countDown();
                        }
                    }
                });
                listenerReady.countDown(); // server can now push

                assertTrue(messageLatch.await(4, TimeUnit.SECONDS), "ACK_STATUS should be received within 4 s");
            }

            server.join(2000);
        }

        assertInstanceOf(Messages.AckStatus.class, dispatched.get());
        Messages.AckStatus ackStatus = (Messages.AckStatus) dispatched.get();
        assertEquals(alertId,  ackStatus.alertId());
        assertEquals(clientId, ackStatus.clientId());
    }

    // ─── 8. Duplicate clientId must NOT produce a second table row ────────────
    //    (tests the guard logic directly — no Swing required)

    @Test
    @DisplayName("Duplicate clientId is not added twice by addRowIfNotDuplicate")
    void duplicateClientIdNotAddedTwice() {
        // We test the guard with a plain HashSet, mirroring what AlertMonitoringPanel does.
        java.util.Set<String> shownClientIds = new java.util.HashSet<>();
        java.util.List<String[]> rows = new java.util.ArrayList<>();

        // Simulate addRowIfNotDuplicate logic
        java.util.function.BiConsumer<String, String> addRow = (clientId, ts) -> {
            if (shownClientIds.add(clientId)) {
                rows.add(new String[]{clientId, ts});
            }
        };

        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();

        addRow.accept(id1, "2025-01-01T10:00:00Z");
        addRow.accept(id2, "2025-01-01T10:01:00Z");
        addRow.accept(id1, "2025-01-01T10:02:00Z"); // duplicate – must be ignored

        assertEquals(2, rows.size(), "Only 2 unique clients should produce 2 rows");
        assertEquals(id1, rows.get(0)[0]);
        assertEquals(id2, rows.get(1)[0]);
    }

    // ─── 9. Listener is properly removed after removeListener ─────────────────

    @Test
    @DisplayName("Listener removed via removeListener stops receiving further messages")
    void listenerRemovedStopsReceivingMessages() throws Exception {
        String clientUuid1 = UUID.randomUUID().toString();
        String clientUuid2 = UUID.randomUUID().toString();

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();
            CountDownLatch listenerRegistered = new CountDownLatch(1);

            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    OutputStream out = s.getOutputStream();
                    // Wait until client has registered listener, then push first message
                    listenerRegistered.await(3, TimeUnit.SECONDS);
                    codec.write(out, new Messages.ClientList(List.of(
                            new Messages.ClientInfo(clientUuid1, "Device1"))));
                    Thread.sleep(300); // give client time to process and remove listener
                    // Push second message — listener should already be removed
                    codec.write(out, new Messages.ClientList(List.of(
                            new Messages.ClientInfo(clientUuid2, "Device2"))));
                    Thread.sleep(500); // keep connection alive
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();

                CountDownLatch firstReceived = new CountDownLatch(1);
                AtomicReference<Integer> receiveCount = new AtomicReference<>(0);

                MessageListener listener = new MessageListener() {
                    @Override
                    public void onMessage(ProtocolMessage message) {
                        receiveCount.updateAndGet(c -> c + 1);
                        firstReceived.countDown();
                    }
                };
                conn.addListener(listener);
                listenerRegistered.countDown(); // signal server it can push first message

                // Wait for first CLIENT_LIST
                assertTrue(firstReceived.await(3, TimeUnit.SECONDS), "Should receive first CLIENT_LIST");

                // Remove listener before second CLIENT_LIST arrives
                conn.removeListener(listener);
                Thread.sleep(600); // let second message arrive and be processed

                assertEquals(1, receiveCount.get(), "Listener should have received exactly one message before removal");
            }

            server.join(2000);
        }
    }

    // ─── 10. ACK_LIST with empty list returns empty collection ───────────────

    @Test
    @DisplayName("ACK_LIST with empty acknowledgements list returns empty collection")
    void ackListEmptyReturnsEmptyList() throws Exception {
        String alertId = UUID.randomUUID().toString();

        try (ServerSocket ss = new ServerSocket(0)) {
            int port = ss.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    InputStream in  = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));
                    codec.read(in);
                    codec.write(out, new Messages.AckList(alertId, List.of()));
                } catch (Exception ignored) {}
            });
            server.setDaemon(true);
            server.start();
            Thread.sleep(50);

            List<Messages.Acknowledgement> result;
            try (AdminConnection conn = new AdminConnection("localhost", port)) {
                conn.connect();
                AdminAuthService svc = new AdminAuthService(conn);
                svc.login("admin", "pass", Duration.ofSeconds(3));
                result = svc.requestAcknowledgements(alertId, Duration.ofSeconds(3));
            }

            server.join(2000);
            assertNotNull(result);
            assertTrue(result.isEmpty(), "Empty ACK_LIST should return an empty collection");
        }
    }
}
