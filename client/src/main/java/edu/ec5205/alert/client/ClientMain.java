package edu.ec5205.alert.client;

import edu.ec5205.alert.client.console.ConsoleClient;

/**
 * Main application entry point for the Emergency Alert Receiver client.
 */
public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) {
        // By default or when --console is specified, run the console client.
        // In Phase 3, this will also launch the Swing desktop application when running in a graphical environment without --console.
        ConsoleClient.main(args);
    }
}
