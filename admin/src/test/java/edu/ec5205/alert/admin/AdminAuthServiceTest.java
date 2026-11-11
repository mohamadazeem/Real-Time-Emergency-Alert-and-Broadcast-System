package edu.ec5205.alert.admin;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.admin.service.AdminAuthService.LoginOutcome;
import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.MessageType;
import edu.ec5205.alert.common.Messages;
import edu.ec5205.alert.common.Priority;
import edu.ec5205.alert.common.ProtocolMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AdminAuthServiceTest {

    private final JsonMessageCodec codec = new JsonMessageCodec();

    @Test
    @DisplayName("Successful login sets session and returns success outcome")
    void successfulLogin() throws Exception {
        AtomicReference<ProtocolMessage> receivedLogin = new AtomicReference<>();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    ProtocolMessage msg = codec.read(in);
                    receivedLogin.set(msg);
                    if (msg instanceof Messages.Login login && "admin".equals(login.username()) && "pass123".equals(login.password())) {
                        codec.write(out, new Messages.LoginResult(true));
                    } else {
                        codec.write(out, new Messages.LoginResult(false));
                    }
                    codec.read(in); // Keep connection open until client closes or sends another message
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);

                LoginOutcome outcome = authService.login("admin", "pass123", Duration.ofSeconds(3));

                assertTrue(outcome.successful());
                assertTrue(authService.isAuthenticated());
                assertTrue(authService.getSession().isPresent());
                assertEquals("admin", authService.getSession().get().username());

                assertNotNull(receivedLogin.get());
                assertEquals(MessageType.LOGIN, receivedLogin.get().type());
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("Failed login with invalid credentials does not create session")
    void failedLogin() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(false));
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);

                LoginOutcome outcome = authService.login("admin", "wrong_pass", Duration.ofSeconds(3));

                assertFalse(outcome.successful());
                assertFalse(authService.isAuthenticated());
                assertTrue(authService.getSession().isEmpty());
                assertTrue(outcome.message().contains("Invalid username or password"));
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("Server error message during login is handled cleanly")
    void serverErrorDuringLogin() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in);
                    codec.write(out, new Messages.ErrorMessage("UNAUTHORIZED", "Account locked", null));
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);

                LoginOutcome outcome = authService.login("admin", "any", Duration.ofSeconds(3));

                assertFalse(outcome.successful());
                assertFalse(authService.isAuthenticated());
                assertTrue(outcome.message().contains("UNAUTHORIZED"));
                assertTrue(outcome.message().contains("Account locked"));
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("Login timeout produces failed outcome without throwing uncaught exception")
    void loginTimeout() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    codec.read(in);
                    // Sleep to force client timeout
                    Thread.sleep(1000);
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);

                LoginOutcome outcome = authService.login("admin", "secret", Duration.ofMillis(200));

                assertFalse(outcome.successful());
                assertTrue(outcome.message().toLowerCase().contains("timed out"));
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("Unauthenticated post-login queries throw IllegalStateException")
    void unauthenticatedQueriesRejected() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);

                assertThrows(IllegalStateException.class, () ->
                        authService.requestConnectedClients(Duration.ofSeconds(1)));

                assertThrows(IllegalStateException.class, () ->
                        authService.requestHistory(10, Duration.ofSeconds(1)));
            }
        }
    }

    @Test
    @DisplayName("Authenticated GET_CLIENTS request and response")
    void authenticatedGetClients() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    // 1. Read login
                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));

                    // 2. Read GET_CLIENTS
                    ProtocolMessage msg = codec.read(in);
                    if (msg instanceof Messages.GetClients) {
                        codec.write(out, new Messages.ClientList(List.of(
                                new Messages.ClientInfo("c1111111-1111-1111-1111-111111111111", "Lab PC 1"),
                                new Messages.ClientInfo("c2222222-2222-2222-2222-222222222222", "Library PC")
                        )));
                    }
                    codec.read(in); // Keep connection open until client closes
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);
                assertTrue(authService.login("admin", "secret", Duration.ofSeconds(3)).successful());

                List<Messages.ClientInfo> clients = authService.requestConnectedClients(Duration.ofSeconds(3));
                assertEquals(2, clients.size());
                assertEquals("Lab PC 1", clients.get(0).name());
                assertEquals("Library PC", clients.get(1).name());
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("Authenticated GET_HISTORY request and response")
    void authenticatedGetHistory() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    // 1. Read login
                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));

                    // 2. Read GET_HISTORY
                    ProtocolMessage msg = codec.read(in);
                    if (msg instanceof Messages.GetHistory) {
                        codec.write(out, new Messages.History(List.of(
                                new Messages.Alert("a1111111-1111-1111-1111-111111111111", "2026-11-09T20:00:00Z",
                                        "Fire Alarm", "Evacuate south wing", Category.FIRE, Priority.CRITICAL)
                        )));
                    }
                    codec.read(in); // Keep connection open until client closes
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);
                assertTrue(authService.login("admin", "secret", Duration.ofSeconds(3)).successful());

                List<Messages.Alert> history = authService.requestHistory(10, Duration.ofSeconds(3));
                assertEquals(1, history.size());
                assertEquals("Fire Alarm", history.get(0).title());
                assertEquals(Priority.CRITICAL, history.get(0).priority());
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("Logout invalidates active session")
    void logoutInvalidatesSession() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));
                    codec.read(in); // Keep connection open until client closes
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);
                assertTrue(authService.login("admin", "secret", Duration.ofSeconds(3)).successful());
                assertTrue(authService.isAuthenticated());

                authService.logout();
                assertFalse(authService.isAuthenticated());
                assertTrue(authService.getSession().isEmpty());
            }

            server.join(2000);
        }
    }
}
