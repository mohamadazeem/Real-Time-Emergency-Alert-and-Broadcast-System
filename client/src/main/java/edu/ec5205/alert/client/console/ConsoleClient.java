package edu.ec5205.alert.client.console;

import edu.ec5205.alert.client.model.ClientIdentity;
import edu.ec5205.alert.client.model.ConnectionState;
import edu.ec5205.alert.client.net.ClientListener;
import edu.ec5205.alert.client.net.ClientNetworkManager;
import edu.ec5205.alert.client.storage.ClientIdentityStorage;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Messages.ErrorMessage;
import edu.ec5205.alert.common.Messages.Registered;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Basic console-based emergency alert receiver application.
 */
public final class ConsoleClient implements ClientListener {
    private final String host;
    private final int port;
    private final String clientName;
    private final Path identityPath;
    private final ClientNetworkManager networkManager = new ClientNetworkManager();
    private final CountDownLatch registrationLatch = new CountDownLatch(1);
    private volatile boolean registrationSuccess = false;

    public ConsoleClient(String host, int port, String clientName, Path identityPath) {
        this.host = host;
        this.port = port;
        this.clientName = clientName;
        this.identityPath = identityPath != null ? identityPath : ClientIdentityStorage.defaultPath();
        this.networkManager.addListener(this);
    }

    public void start() {
        System.out.println("========================================");
        System.out.println(" EMERGENCY ALERT RECEIVER - CONSOLE");
        System.out.println("========================================");
        System.out.println();

        ClientIdentity identity = ClientIdentityStorage.loadOrCreate(identityPath, clientName);
        System.out.println("Client Name: " + identity.clientName());
        System.out.println("Client ID: " + identity.clientId());
        System.out.println();

        System.out.println("Connecting to server " + host + ":" + port + "...");
        try {
            networkManager.connect(host, port, identity);
            System.out.println("Connection established.");

            // Wait for registration confirmation
            boolean completed = registrationLatch.await(5, TimeUnit.SECONDS);
            if (!completed || !registrationSuccess) {
                System.err.println("Registration timed out or failed.");
                networkManager.disconnect();
                return;
            }

            System.out.println("Registration successful.");
            System.out.println();
            System.out.println("Waiting for emergency alerts...");
            System.out.println();

            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShutting down console receiver...");
                networkManager.disconnect();
            }, "console-shutdown"));

        } catch (IOException e) {
            System.err.println("Connection failure: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        networkManager.disconnect();
    }

    public ClientNetworkManager getNetworkManager() {
        return networkManager;
    }

    @Override
    public void onRegistered(Registered registered) {
        registrationSuccess = true;
        registrationLatch.countDown();
    }

    @Override
    public void onAlertReceived(Alert alert) {
        System.out.println("========================================");
        System.out.println(" NEW EMERGENCY ALERT");
        System.out.println("========================================");
        System.out.println("Title: " + alert.title());
        System.out.println("Message: " + alert.message());
        System.out.println("Category: " + alert.category());
        System.out.println("Priority: " + alert.priority());
        System.out.println("Timestamp: " + alert.createdAt());
        System.out.println("Alert ID: " + alert.id());
        System.out.println("========================================");
        System.out.println();
    }

    @Override
    public void onError(ErrorMessage error) {
        System.err.println("[ERROR] Server returned error (" + error.code() + "): " + error.message());
        registrationLatch.countDown();
    }

    @Override
    public void onDisconnected(String reason) {
        System.out.println("[NOTICE] Disconnected from server: " + reason);
    }

    @Override
    public void onStateChanged(ConnectionState oldState, ConnectionState newState) {
        // Log state changes if needed
    }

    public static void main(String[] args) {
        edu.ec5205.alert.client.ClientMain.Options options = edu.ec5205.alert.client.ClientMain.Options.parse(args);
        ConsoleClient client = new ConsoleClient(options.host(), options.port(), options.name(), options.identityPath());
        try { client.start(); }
        catch (java.io.UncheckedIOException | IllegalStateException e) {
            System.err.println("Unable to load/save receiver identity. Restore the identity file or choose a new --id-file path.");
            client.stop();
            return;
        }

        // Keep main thread alive while listening
        try {
            while (client.networkManager.isConnected() || client.networkManager.getState() == ConnectionState.CONNECTING) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException ignored) {}
    }
}

