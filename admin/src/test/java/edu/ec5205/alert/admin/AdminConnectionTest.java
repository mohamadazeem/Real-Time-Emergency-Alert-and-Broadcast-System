package edu.ec5205.alert.admin;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.network.MessageListener;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.MessageType;
import edu.ec5205.alert.common.Messages;
import edu.ec5205.alert.common.ProtocolException;
import edu.ec5205.alert.common.ProtocolMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AdminConnectionTest {

    private final JsonMessageCodec codec = new JsonMessageCodec();

    @Test
    @DisplayName("Connection refused when server port is not open")
    void connectionRefused() throws IOException {
        int unusedPort;
        try (ServerSocket s = new ServerSocket(0)) {
            unusedPort = s.getLocalPort();
        } // Closed immediately, so unusedPort is closed

        try (AdminConnection connection = new AdminConnection("localhost", unusedPort)) {
            assertThrows(IOException.class, connection::connect);
            assertFalse(connection.isConnected());
            assertTrue(connection.isClosed());
        }
    }

    @Test
    @DisplayName("Clean connect, send, and disconnect lifecycle")
    void connectSendAndCleanShutdown() throws Exception {
        CountDownLatch clientConnected = new CountDownLatch(1);
        CountDownLatch messageReceived = new CountDownLatch(1);
        AtomicReference<ProtocolMessage> serverReceived = new AtomicReference<>();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    clientConnected.countDown();
                    InputStream in = s.getInputStream();
                    ProtocolMessage msg = codec.read(in);
                    serverReceived.set(msg);
                    messageReceived.countDown();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            try (AdminConnection client = new AdminConnection("localhost", port)) {
                client.connect();
                assertTrue(client.isConnected());
                assertTrue(clientConnected.await(3, TimeUnit.SECONDS));

                client.send(new Messages.Login("admin", "secret123"));
                assertTrue(messageReceived.await(3, TimeUnit.SECONDS));
                assertNotNull(serverReceived.get());
                assertEquals(MessageType.LOGIN, serverReceived.get().type());

                client.close();
                assertTrue(client.isClosed());
                assertFalse(client.isConnected());
            }

            serverThread.join(2000);
        }
    }

    @Test
    @DisplayName("Malformed JSON response triggers error handling")
    void malformedServerResponse() throws Exception {
        CountDownLatch errorOccurred = new CountDownLatch(1);
        AtomicReference<Throwable> capturedError = new AtomicReference<>();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    OutputStream out = s.getOutputStream();
                    // Send non-JSON garbage followed by newline
                    out.write("NOT_A_VALID_JSON_OBJECT\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            try (AdminConnection client = new AdminConnection("localhost", port)) {
                client.addListener(new MessageListener() {
                    @Override
                    public void onMessage(ProtocolMessage message) {}

                    @Override
                    public void onError(Throwable error) {
                        capturedError.set(error);
                        errorOccurred.countDown();
                    }
                });

                client.connect();
                assertTrue(errorOccurred.await(3, TimeUnit.SECONDS));
                assertNotNull(capturedError.get());
                assertInstanceOf(ProtocolException.class, capturedError.get());
            }

            serverThread.join(2000);
        }
    }

    @Test
    @DisplayName("Oversized frame (>65536 bytes) is rejected before unbounded memory allocation")
    void oversizedFrameRejection() throws Exception {
        CountDownLatch errorOccurred = new CountDownLatch(1);
        AtomicReference<Throwable> capturedError = new AtomicReference<>();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    OutputStream out = s.getOutputStream();
                    // Send 70,000 bytes with no newline
                    byte[] oversized = new byte[70000];
                    Arrays.fill(oversized, (byte) 'A');
                    out.write(oversized);
                    out.flush();
                } catch (IOException ignored) {}
            });
            serverThread.start();

            try (AdminConnection client = new AdminConnection("localhost", port)) {
                client.addListener(new MessageListener() {
                    @Override
                    public void onMessage(ProtocolMessage message) {}

                    @Override
                    public void onError(Throwable error) {
                        capturedError.set(error);
                        errorOccurred.countDown();
                    }
                });

                client.connect();
                assertTrue(errorOccurred.await(3, TimeUnit.SECONDS));
                assertNotNull(capturedError.get());
                assertInstanceOf(ProtocolException.class, capturedError.get());
                assertTrue(capturedError.get().getMessage().contains("65536"));
            }

            serverThread.join(2000);
        }
    }

    @Test
    @DisplayName("sendAndAwait correctly matches and returns server response")
    void sendAndAwaitResponse() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    ProtocolMessage incoming = codec.read(in);
                    if (incoming instanceof Messages.Login) {
                        codec.write(out, new Messages.LoginResult(true));
                    }
                } catch (IOException ignored) {}
            });
            serverThread.start();

            try (AdminConnection client = new AdminConnection("localhost", port)) {
                client.connect();
                ProtocolMessage response = client.sendAndAwait(
                        new Messages.Login("admin", "pwd"),
                        msg -> msg.type() == MessageType.LOGIN_RESULT,
                        Duration.ofSeconds(3)
                );

                assertInstanceOf(Messages.LoginResult.class, response);
                assertTrue(((Messages.LoginResult) response).success());
            }

            serverThread.join(2000);
        }
    }

    @Test
    @DisplayName("Clean shutdown fires onClosed on MessageListener")
    void cleanShutdownNotifiesListener() throws Exception {
        CountDownLatch closedLatch = new CountDownLatch(1);
        AtomicBoolean closedCalled = new AtomicBoolean(false);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            try (AdminConnection client = new AdminConnection("localhost", port)) {
                client.addListener(new MessageListener() {
                    @Override
                    public void onMessage(ProtocolMessage message) {}

                    @Override
                    public void onClosed() {
                        closedCalled.set(true);
                        closedLatch.countDown();
                    }
                });

                client.connect();
                client.close();

                assertTrue(closedLatch.await(2, TimeUnit.SECONDS));
                assertTrue(closedCalled.get());
            }
        }
    }
}

