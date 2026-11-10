package edu.ec5205.alert.admin;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.admin.service.AdminAuthService.LoginOutcome;
import edu.ec5205.alert.admin.ui.LoginFrame;
import edu.ec5205.alert.common.Messages;
import edu.ec5205.alert.common.ProtocolException;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.Console;
import java.io.IOException;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.TimeoutException;

/**
 * Entry point for the Admin application.
 * <p>
 * By default launches the Swing desktop GUI (LoginFrame -> DashboardFrame).
 * Also preserves console mode when launched in a headless environment or with --console.
 */
public final class AdminMain {

    private AdminMain() {}

    public static void main(String[] args) {
        boolean forceConsole = false;
        for (String arg : args) {
            if ("--console".equalsIgnoreCase(arg) || "--cli".equalsIgnoreCase(arg)) {
                forceConsole = true;
                break;
            }
        }

        if (forceConsole || GraphicsEnvironment.isHeadless()) {
            runConsoleMode();
        } else {
            SwingUtilities.invokeLater(() -> {
                LoginFrame loginFrame = new LoginFrame();
                loginFrame.setVisible(true);
            });
        }
    }

    public static void runConsoleMode() {
        System.out.println("===============================================================");
        System.out.println(" Emergency Alert & Broadcast System - Admin Application");
        System.out.println(" Stage 1: Basic Connectivity & Admin Authentication (CLI)");
        System.out.println("===============================================================");

        Scanner scanner = new Scanner(System.in);

        // 1. Collect Connection Details
        System.out.print("Enter server host [" + AdminConnection.DEFAULT_HOST + "]: ");
        String hostInput = scanner.nextLine().trim();
        String host = hostInput.isEmpty() ? AdminConnection.DEFAULT_HOST : hostInput;

        System.out.print("Enter server port [" + AdminConnection.DEFAULT_PORT + "]: ");
        String portInput = scanner.nextLine().trim();
        int port = portInput.isEmpty() ? AdminConnection.DEFAULT_PORT : parsePort(portInput);

        // 2. Collect Credentials (Never print password)
        System.out.print("Enter admin username [admin]: ");
        String userInput = scanner.nextLine().trim();
        String username = userInput.isEmpty() ? "admin" : userInput;

        String password = readPassword(scanner, "Enter admin password: ");

        System.out.println();
        System.out.println("Connecting to " + host + ":" + port + "...");

        try (AdminConnection connection = new AdminConnection(host, port)) {
            // Register shutdown hook for clean disconnection on Ctrl+C
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (connection.isConnected()) {
                    System.out.println("\nShutting down admin connection...");
                    connection.close();
                }
            }, "admin-shutdown"));

            connection.connect();
            System.out.println("Connected to server successfully (TCP socket established).");

            AdminAuthService authService = new AdminAuthService(connection);
            System.out.println("Authenticating as '" + username + "'...");

            LoginOutcome outcome = authService.login(username, password);

            if (!outcome.successful()) {
                System.err.println("\n[!] LOGIN FAILED: " + outcome.message());
                System.err.println("Closing connection.");
                return;
            }

            System.out.println("\n[+] " + outcome.message());
            System.out.println("[+] Active Session: " + authService.getSession().orElse(null));

            // 3. Post-Login Interactive Diagnostics Menu
            runPostLoginMenu(scanner, authService, connection);

        } catch (ConnectException e) {
            System.err.println("\n[!] Connection refused: Unable to connect to " + host + ":" + port + ".");
            System.err.println("    Please ensure the server process is running and reachable on this port.");
        } catch (IOException e) {
            System.err.println("\n[!] Network error: " + e.getMessage());
        } catch (Exception e) {
            System.err.println("\n[!] Unexpected error: " + e.getMessage());
        } finally {
            System.out.println("\nAdmin application terminated cleanly.");
        }
    }

    private static void runPostLoginMenu(Scanner scanner, AdminAuthService authService, AdminConnection connection) {
        boolean running = true;
        while (running && connection.isConnected()) {
            System.out.println("\n---------------------------------------------------------------");
            System.out.println(" Post-Login Diagnostics Menu");
            System.out.println("---------------------------------------------------------------");
            System.out.println(" [1] Test GET_CLIENTS (Query connected receiver clients)");
            System.out.println(" [2] Test GET_HISTORY (Query recent alert history)");
            System.out.println(" [3] View Connection & Session Status");
            System.out.println(" [4] Disconnect and Exit");
            System.out.print(" Select an option [1-4]: ");

            String choice = scanner.nextLine().trim();
            switch (choice) {
                case "1" -> testGetClients(authService);
                case "2" -> testGetHistory(scanner, authService);
                case "3" -> showStatus(authService, connection);
                case "4" -> {
                    System.out.println("Disconnecting from server...");
                    connection.close();
                    running = false;
                }
                default -> System.out.println("Invalid selection. Please choose 1, 2, 3, or 4.");
            }
        }
    }

    private static void testGetClients(AdminAuthService authService) {
        System.out.println("\nSending GET_CLIENTS request to server...");
        try {
            List<Messages.ClientInfo> clients = authService.requestConnectedClients(Duration.ofSeconds(5));
            System.out.println("[+] Received CLIENT_LIST with " + clients.size() + " client(s):");
            if (clients.isEmpty()) {
                System.out.println("    (No receiver clients currently connected)");
            } else {
                for (Messages.ClientInfo client : clients) {
                    System.out.println("    - ID: " + client.clientId() + " | Name: " + client.name());
                }
            }
        } catch (ProtocolException e) {
            System.out.println("[*] Server response: " + e.getMessage());
        } catch (TimeoutException e) {
            System.err.println("[!] Request timed out waiting for server response.");
        } catch (Exception e) {
            System.err.println("[!] GET_CLIENTS failed: " + e.getMessage());
        }
    }

    private static void testGetHistory(Scanner scanner, AdminAuthService authService) {
        System.out.print("Enter history limit (1-100) [10]: ");
        String input = scanner.nextLine().trim();
        int limit = input.isEmpty() ? 10 : parseLimit(input);

        System.out.println("\nSending GET_HISTORY (limit=" + limit + ") request to server...");
        try {
            List<Messages.Alert> history = authService.requestHistory(limit, Duration.ofSeconds(5));
            System.out.println("[+] Received HISTORY with " + history.size() + " alert(s):");
            if (history.isEmpty()) {
                System.out.println("    (No alerts recorded in server history)");
            } else {
                for (Messages.Alert alert : history) {
                    System.out.println("    - [" + alert.createdAt() + "] [" + alert.priority() + " / " + alert.category() + "] "
                            + alert.title() + ": " + alert.message() + " (ID: " + alert.id() + ")");
                }
            }
        } catch (ProtocolException e) {
            System.out.println("[*] Server response: " + e.getMessage());
        } catch (TimeoutException e) {
            System.err.println("[!] Request timed out waiting for server response.");
        } catch (Exception e) {
            System.err.println("[!] GET_HISTORY failed: " + e.getMessage());
        }
    }

    private static void showStatus(AdminAuthService authService, AdminConnection connection) {
        System.out.println("\nConnection Status:");
        System.out.println("  Server Host: " + connection.host());
        System.out.println("  Server Port: " + connection.port());
        System.out.println("  Socket Connected: " + connection.isConnected());
        System.out.println("  Authenticated: " + authService.isAuthenticated());
        authService.getSession().ifPresent(s -> {
            System.out.println("  Admin Username: " + s.username());
            System.out.println("  Session Start:  " + s.authenticatedAt());
        });
    }

    private static int parsePort(String input) {
        try {
            int port = Integer.parseInt(input);
            if (port < 1 || port > 65535) {
                System.out.println("Port must be between 1 and 65535. Falling back to default: " + AdminConnection.DEFAULT_PORT);
                return AdminConnection.DEFAULT_PORT;
            }
            return port;
        } catch (NumberFormatException e) {
            System.out.println("Invalid port number. Falling back to default: " + AdminConnection.DEFAULT_PORT);
            return AdminConnection.DEFAULT_PORT;
        }
    }

    private static int parseLimit(String input) {
        try {
            int limit = Integer.parseInt(input);
            if (limit < 1 || limit > 100) {
                System.out.println("Limit must be between 1 and 100. Using default: 10");
                return 10;
            }
            return limit;
        } catch (NumberFormatException e) {
            System.out.println("Invalid limit number. Using default: 10");
            return 10;
        }
    }

    private static String readPassword(Scanner scanner, String prompt) {
        Console console = System.console();
        if (console != null) {
            char[] passwordChars = console.readPassword(prompt);
            return passwordChars != null ? new String(passwordChars) : "";
        }
        System.out.print(prompt);
        return scanner.nextLine();
    }
}
