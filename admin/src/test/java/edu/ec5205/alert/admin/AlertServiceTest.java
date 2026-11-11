package edu.ec5205.alert.admin;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.admin.service.AlertService;
import edu.ec5205.alert.admin.service.AlertService.AlertCreationResult;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AlertServiceTest {

    private final JsonMessageCodec codec = new JsonMessageCodec();

    // 1. Validation Tests

    @Test
    @DisplayName("Valid alert input passes validation")
    void validAlertInputPasses() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            assertDoesNotThrow(() -> alertService.validateAlertInput(
                    "Fire in Lab B",
                    "Evacuate via east staircase immediately.",
                    Category.FIRE,
                    Priority.CRITICAL
            ));
        }
    }

    @Test
    @DisplayName("Empty or null title is rejected")
    void emptyTitleRejected() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput(null, "Valid message", Category.FIRE, highDefault()));
            assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput("", "Valid message", Category.FIRE, Priority.CRITICAL));
        }
    }

    @Test
    @DisplayName("Whitespace-only title is rejected")
    void whitespaceOnlyTitleRejected() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput("    \t  \n  ", "Valid message", Category.GENERAL, Priority.LOW));
        }
    }

    @Test
    @DisplayName("Title exceeding 120 characters is rejected")
    void titleExceeding120CharsRejected() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            String longTitle = "A".repeat(121);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput(longTitle, "Valid message", Category.WEATHER, Priority.MEDIUM));
            assertTrue(ex.getMessage().contains("120"));
        }
    }

    @Test
    @DisplayName("Empty or null message is rejected")
    void emptyMessageRejected() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput("Valid title", null, Category.FIRE, Priority.CRITICAL));
            assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput("Valid title", "", Category.FIRE, Priority.CRITICAL));
        }
    }

    @Test
    @DisplayName("Whitespace-only message is rejected")
    void whitespaceOnlyMessageRejected() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput("Valid title", "   \n\t   ", Category.SECURITY, Priority.CRITICAL));
        }
    }

    @Test
    @DisplayName("Message exceeding 4000 characters is rejected")
    void messageExceeding4000CharsRejected() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            String longMessage = "M".repeat(4001);
            IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput("Valid title", longMessage, Category.GENERAL, Priority.LOW));
            assertTrue(ex.getMessage().contains("4000"));
        }
    }

    @Test
    @DisplayName("Null category or priority is rejected")
    void nullCategoryOrPriorityRejected() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput("Title", "Message", null, Priority.CRITICAL));
            assertThrows(IllegalArgumentException.class, () ->
                    alertService.validateAlertInput("Title", "Message", Category.FIRE, null));
        }
    }

    // 2. Network Protocol Exchange Tests

    @Test
    @DisplayName("CREATE_ALERT request construction and ALERT_ACCEPTED response handling")
    void createAlertAndAcceptedResponse() throws Exception {
        AtomicReference<Messages.CreateAlert> receivedRequest = new AtomicReference<>();
        String expectedAlertId = UUID.randomUUID().toString();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();

                    // 1. Handshake login
                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));

                    // 2. Read CREATE_ALERT
                    ProtocolMessage msg = codec.read(in);
                    if (msg instanceof Messages.CreateAlert req) {
                        receivedRequest.set(req);
                        // Respond with ALERT_ACCEPTED containing identical requestId
                        codec.write(out, new Messages.AlertAccepted(req.requestId(), expectedAlertId));
                    }
                    codec.read(in); // Keep open until client completes
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);
                assertTrue(authService.login("admin", "secret", Duration.ofSeconds(3)).successful());

                AlertService alertService = new AlertService(connection, authService);
                AlertCreationResult result = alertService.broadcastAlert(
                        "Severe Weather Warning",
                        "Tornado warning in effect. Seek shelter.",
                        Category.WEATHER,
                        Priority.CRITICAL,
                        Duration.ofSeconds(3)
                );

                assertTrue(result.successful());
                assertEquals(expectedAlertId, result.alertId());
                assertNotNull(result.requestId());

                // Verify wire payload created by AlertService
                Messages.CreateAlert wireReq = receivedRequest.get();
                assertNotNull(wireReq);
                assertEquals(MessageType.CREATE_ALERT, wireReq.type());
                assertEquals("Severe Weather Warning", wireReq.title());
                assertEquals("Tornado warning in effect. Seek shelter.", wireReq.message());
                assertEquals(Category.WEATHER, wireReq.category());
                assertEquals(Priority.CRITICAL, wireReq.priority());
                assertEquals(result.requestId(), wireReq.requestId());
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("Server ERROR response handling during CREATE_ALERT")
    void serverErrorResponseHandling() throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();

                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));

                    ProtocolMessage msg = codec.read(in);
                    if (msg instanceof Messages.CreateAlert req) {
                        codec.write(out, new Messages.ErrorMessage("INVALID_ALERT", "Database storage full", req.requestId()));
                    }
                    codec.read(in);
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);
                assertTrue(authService.login("admin", "secret", Duration.ofSeconds(3)).successful());

                AlertService alertService = new AlertService(connection, authService);
                AlertCreationResult result = alertService.broadcastAlert(
                        "Fire Alarm",
                        "Evacuate",
                        Category.FIRE,
                        Priority.CRITICAL,
                        Duration.ofSeconds(3)
                );

                assertFalse(result.successful());
                assertNull(result.alertId());
                assertTrue(result.message().contains("INVALID_ALERT"));
                assertTrue(result.message().contains("Database storage full"));
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("RequestId correlation ensures matching response is returned")
    void requestIdCorrelation() throws Exception {
        String trueAlertId = UUID.randomUUID().toString();

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();

            Thread server = new Thread(() -> {
                try (Socket s = serverSocket.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();

                    codec.read(in);
                    codec.write(out, new Messages.LoginResult(true));

                    ProtocolMessage msg = codec.read(in);
                    if (msg instanceof Messages.CreateAlert req) {
                        // First send an unrelated response with a different requestId
                        codec.write(out, new Messages.AlertAccepted(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
                        // Then send the matching response
                        codec.write(out, new Messages.AlertAccepted(req.requestId(), trueAlertId));
                    }
                    codec.read(in);
                } catch (Exception ignored) {}
            });
            server.start();

            try (AdminConnection connection = new AdminConnection("localhost", port)) {
                connection.connect();
                AdminAuthService authService = new AdminAuthService(connection);
                assertTrue(authService.login("admin", "secret", Duration.ofSeconds(3)).successful());

                AlertService alertService = new AlertService(connection, authService);
                AlertCreationResult result = alertService.broadcastAlert(
                        "Correlated Alert",
                        "Checking requestId correlation",
                        Category.SECURITY,
                        Priority.MEDIUM,
                        Duration.ofSeconds(3)
                );

                assertTrue(result.successful());
                assertEquals(trueAlertId, result.alertId());
            }

            server.join(2000);
        }
    }

    @Test
    @DisplayName("Unauthenticated alert submission is rejected before network write")
    void unauthenticatedAlertRejected() throws Exception {
        try (AdminConnection connection = new AdminConnection("localhost", 5050)) {
            AdminAuthService authService = new AdminAuthService(connection);
            AlertService alertService = new AlertService(connection, authService);

            AlertCreationResult result = alertService.broadcastAlert(
                    "Title", "Message", Category.GENERAL, Priority.LOW
            );

            assertFalse(result.successful());
            assertTrue(result.message().contains("Authentication required"));
        }
    }

    private static Priority highDefault() {
        return Priority.CRITICAL;
    }
}
