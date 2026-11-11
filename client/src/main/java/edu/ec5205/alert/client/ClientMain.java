package edu.ec5205.alert.client;

import edu.ec5205.alert.client.console.ConsoleClient;
import edu.ec5205.alert.client.ui.ReceiverFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import edu.ec5205.alert.client.storage.ClientIdentityStorage;

/**
 * Main application entry point for the Emergency Alert Receiver client.
 */
public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.console()) { ConsoleClient.main(args); return; }
            if (GraphicsEnvironment.isHeadless()) {
                System.err.println("Receiver GUI requires a graphical desktop. Use --console for receive-only diagnostics.");
                return;
            }
            SwingUtilities.invokeLater(() -> new ReceiverFrame(options.host(), options.port(), options.name(), options.identityPath()).setVisible(true));
        } catch (IllegalArgumentException e) { System.err.println("Receiver options: " + e.getMessage()); }
    }

    public record Options(String host, int port, String name, Path identityPath, boolean console) {
        public static Options parse(String[] args) {
            String host = "127.0.0.1", name = "Receiver 01";
            int port = 5050, positional = 0; boolean console = false;
            Path path = ClientIdentityStorage.defaultPath();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.equalsIgnoreCase("--console")) { console = true; continue; }
                if (arg.startsWith("--")) {
                    if (i + 1 == args.length) throw new IllegalArgumentException("Missing value for " + arg);
                    String value = args[++i];
                    switch (arg.toLowerCase(java.util.Locale.ROOT)) {
                        case "--host" -> host = value;
                        case "--port" -> port = Integer.parseInt(value);
                        case "--name" -> name = value;
                        case "--id-file" -> path = Path.of(value);
                        default -> throw new IllegalArgumentException("Unknown option " + arg);
                    }
                } else {
                    switch (positional++) {
                        case 0 -> host = arg;
                        case 1 -> port = Integer.parseInt(arg);
                        case 2 -> name = arg;
                        case 3 -> path = Path.of(arg);
                        default -> throw new IllegalArgumentException("Too many arguments.");
                    }
                }
            }
            if (host.isBlank() || name.isBlank() || port < 1 || port > 65535) throw new IllegalArgumentException("Provide host, display name and port 1-65535.");
            return new Options(host, port, name, path, console);
        }
    }
}
