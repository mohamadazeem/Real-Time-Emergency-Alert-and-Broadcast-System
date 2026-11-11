package edu.ec5205.alert.client;

import edu.ec5205.alert.client.model.AckStatusState;
import edu.ec5205.alert.client.model.ClientIdentity;
import edu.ec5205.alert.client.model.ConnectionState;
import edu.ec5205.alert.client.model.ReceivedAlert;
import edu.ec5205.alert.client.net.ClientListener;
import edu.ec5205.alert.client.net.ClientNetworkManager;
import edu.ec5205.alert.client.service.AlertHistoryManager;
import edu.ec5205.alert.client.storage.ClientIdentityStorage;
import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.Ack;
import edu.ec5205.alert.common.Messages.AckResult;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Messages.AlertMessage;
import edu.ec5205.alert.common.Messages.RegisterClient;
import edu.ec5205.alert.common.Messages.Registered;
import edu.ec5205.alert.common.Priority;
import edu.ec5205.alert.common.ProtocolMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class ConsoleClientIntegrationTest {

    private final JsonMessageCodec codec = new JsonMessageCodec();
    private ServerSocket testServer;
    private int serverPort;
    private final Set<Socket> serverAcceptedSockets = ConcurrentHashMap.newKeySet();
    private final CopyOnWriteArrayList<RegisterClient> serverReceivedRegistrations = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Ack> serverReceivedAcks = new CopyOnWriteArrayList<>();
    private Thread serverAcceptThread;
    private volatile boolean serverRunning = true;

    @BeforeEach
    void startMockServer() throws IOException {
        testServer = new ServerSocket(0); // Any available port
        serverPort = testServer.getLocalPort();
        serverRunning = true;

        serverAcceptThread = new Thread(() -> {
            try {
                while (serverRunning && !testServer.isClosed()) {
                    Socket socket = testServer.accept();
                    serverAcceptedSockets.add(socket);
                    new Thread(() -> handleServerClient(socket)).start();
                }
            } catch (IOException ignored) {}
        }, "mock-server-accept");
        serverAcceptThread.start();
    }

    private void handleServerClient(Socket socket) {
        try {
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            while (serverRunning && !socket.isClosed()) {
                ProtocolMessage msg = codec.read(in);
                if (msg == null) break;

                if (msg instanceof RegisterClient reg) {
                    serverReceivedRegistrations.add(reg);
                    // Send REGISTERED back
                    codec.write(out, new Registered(reg.clientId()));
                } else if (msg instanceof Ack ack) {
                    serverReceivedAcks.add(ack);
                    // Send ACK_RESULT back
                    codec.write(out, new AckResult(ack.alertId(), true));
                }
            }
        } catch (Exception ignored) {}
    }

    @AfterEach
    void stopMockServer() {
        serverRunning = false;
        try {
            if (testServer != null) testServer.close();
        } catch (IOException ignored) {}
        for (Socket s : serverAcceptedSockets) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    @Test
    void twoIndependentClientsConnectRegisterAndReceiveBroadcastAlert(@TempDir Path tempDir) throws Exception {
        // Setup two distinct identity files
        Path idPath1 = tempDir.resolve("client-01.json");
        Path idPath2 = tempDir.resolve("client-02.json");

        ClientIdentity id1 = ClientIdentityStorage.loadOrCreate(idPath1, "Receiver 01");
        ClientIdentity id2 = ClientIdentityStorage.loadOrCreate(idPath2, "Receiver 02");

        assertNotEquals(id1.clientId(), id2.clientId(), "Two independent clients must have distinct UUIDs");

        // Client 1 setup
        ClientNetworkManager client1 = new ClientNetworkManager();
        AlertHistoryManager history1 = new AlertHistoryManager();
        CountDownLatch reg1Latch = new CountDownLatch(1);
        CountDownLatch alert1Latch = new CountDownLatch(1);
        CountDownLatch ack1Latch = new CountDownLatch(1);

        client1.addListener(new ClientListener() {
            @Override
            public void onRegistered(Registered reg) {
                reg1Latch.countDown();
            }

            @Override
            public void onAlertReceived(Alert alert) {
                if (history1.addAlert(alert)) {
                    alert1Latch.countDown();
                }
            }

            @Override
            public void onAckResult(AckResult ackResult) {
                history1.updateAckStatus(ackResult.alertId(), AckStatusState.ACKNOWLEDGED);
                ack1Latch.countDown();
            }
        });

        // Client 2 setup
        ClientNetworkManager client2 = new ClientNetworkManager();
        AlertHistoryManager history2 = new AlertHistoryManager();
        CountDownLatch reg2Latch = new CountDownLatch(1);
        CountDownLatch alert2Latch = new CountDownLatch(1);
        CountDownLatch ack2Latch = new CountDownLatch(1);

        client2.addListener(new ClientListener() {
            @Override
            public void onRegistered(Registered reg) {
                reg2Latch.countDown();
            }

            @Override
            public void onAlertReceived(Alert alert) {
                if (history2.addAlert(alert)) {
                    alert2Latch.countDown();
                }
            }

            @Override
            public void onAckResult(AckResult ackResult) {
                history2.updateAckStatus(ackResult.alertId(), AckStatusState.ACKNOWLEDGED);
                ack2Latch.countDown();
            }
        });

        // Connect both clients
        client1.connect("127.0.0.1", serverPort, id1);
        client2.connect("127.0.0.1", serverPort, id2);

        assertTrue(reg1Latch.await(5, TimeUnit.SECONDS), "Client 1 registration must succeed");
        assertTrue(reg2Latch.await(5, TimeUnit.SECONDS), "Client 2 registration must succeed");
        assertEquals(ConnectionState.CONNECTED, client1.getState());
        assertEquals(ConnectionState.CONNECTED, client2.getState());

        // Verify server received both unique registrations
        assertEquals(2, serverReceivedRegistrations.size());
        Set<String> registeredIds = new HashSet<>();
        for (RegisterClient reg : serverReceivedRegistrations) {
            registeredIds.add(reg.clientId());
        }
        assertTrue(registeredIds.contains(id1.clientId()));
        assertTrue(registeredIds.contains(id2.clientId()));

        // Broadcast alert from server to all connected client sockets
        String alertId = UUID.randomUUID().toString();
        Alert broadcastAlert = new Alert(alertId, "2026-11-11T10:30:00Z",
                "Flood Warning", "Heavy rainfall expected.", Category.WEATHER, Priority.CRITICAL);
        AlertMessage alertMessage = new AlertMessage(broadcastAlert);

        for (Socket s : serverAcceptedSockets) {
            codec.write(s.getOutputStream(), alertMessage);
        }

        // Verify both clients received the alert
        assertTrue(alert1Latch.await(5, TimeUnit.SECONDS), "Client 1 must receive the broadcast alert");
        assertTrue(alert2Latch.await(5, TimeUnit.SECONDS), "Client 2 must receive the broadcast alert");

        assertEquals(1, history1.getAllAlerts().size());
        assertEquals(1, history2.getAllAlerts().size());

        ReceivedAlert r1 = history1.getAlert(alertId).orElseThrow();
        ReceivedAlert r2 = history2.getAlert(alertId).orElseThrow();

        assertEquals("Flood Warning", r1.title());
        assertEquals("Flood Warning", r2.title());
        assertEquals(Priority.CRITICAL, r1.priority());
        assertEquals(Priority.CRITICAL, r2.priority());
        assertEquals(Category.WEATHER, r1.category());
        assertEquals(Category.WEATHER, r2.category());

        // Acknowledge from Client 1
        client1.sendAck(alertId);
        assertTrue(ack1Latch.await(5, TimeUnit.SECONDS), "Client 1 must receive ACK_RESULT");
        assertEquals(AckStatusState.ACKNOWLEDGED, history1.getAlert(alertId).orElseThrow().ackStatus());
        assertEquals(AckStatusState.UNACKNOWLEDGED, history2.getAlert(alertId).orElseThrow().ackStatus());

        // Acknowledge from Client 2 independently
        client2.sendAck(alertId);
        assertTrue(ack2Latch.await(5, TimeUnit.SECONDS), "Client 2 must receive ACK_RESULT");
        assertEquals(AckStatusState.ACKNOWLEDGED, history2.getAlert(alertId).orElseThrow().ackStatus());

        // Verify duplicate alert handling: server sends the same alert again
        for (Socket s : serverAcceptedSockets) {
            codec.write(s.getOutputStream(), alertMessage);
        }
        Thread.sleep(200); // Give background thread a moment
        assertEquals(1, history1.getAllAlerts().size(), "Duplicate alert must not increase count");
        assertEquals(1, history2.getAllAlerts().size(), "Duplicate alert must not increase count");

        // Disconnect cleanly
        client1.disconnect();
        client2.disconnect();
        assertEquals(ConnectionState.DISCONNECTED, client1.getState());
        assertEquals(ConnectionState.DISCONNECTED, client2.getState());
    }

    @Test
    void invalidAddressOrPortFailsGracefully() {
        ClientNetworkManager client = new ClientNetworkManager();
        ClientIdentity id = new ClientIdentity(UUID.randomUUID().toString(), "Test");

        assertThrows(IllegalArgumentException.class, () -> client.connect("127.0.0.1", 99999, id));
        assertThrows(IOException.class, () -> client.connect("127.0.0.1", 65530, id)); // Non-existent server
        assertEquals(ConnectionState.DISCONNECTED, client.getState());
    }
}

