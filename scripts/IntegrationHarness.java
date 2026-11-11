import edu.ec5205.alert.admin.network.*;
import edu.ec5205.alert.admin.service.*;
import edu.ec5205.alert.client.model.*;
import edu.ec5205.alert.client.net.*;
import edu.ec5205.alert.client.service.AlertHistoryManager;
import edu.ec5205.alert.client.storage.ClientIdentityStorage;
import edu.ec5205.alert.common.*;
import edu.ec5205.alert.common.Messages.*;
import edu.ec5205.alert.server.ServerMain;
import java.io.*;
import java.awt.Window;
import java.net.ServerSocket;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Real loopback integration, deliberately separate from mock-server module tests. */
public final class IntegrationHarness {
    static final Duration TIMEOUT = Duration.ofSeconds(5);
    static Path runDir;
    static String secret = UUID.randomUUID().toString();
    static int port;
    static String cp = System.getProperty("java.class.path");
    static String java = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
    static final List<Long> latency = new ArrayList<>();

    // Calls the actual production entry point. stdin is only a test shutdown signal:
    // System.exit runs ServerMain's real shutdown hook (unlike forced Windows termination).
    public static final class ServerProcess {
        public static void main(String[] args) throws Exception {
            new Thread(() -> ServerMain.main(new String[0]), "real-server-main").start();
            System.in.read();
            System.exit(0);
        }
    }

    /** Disposable manual GUI fixture. Its two receivers ACK automatically for testing only. */
    public static final class GuiFixture {
        public static void main(String[] args) throws Exception {
            runDir = Files.createTempDirectory(evidenceRoot(args), "gui-");
            Files.createDirectories(runDir);
            port = 5050;
            Process server = null;
            Thread cleanupHook = null;
            try (Receiver a = new Receiver("gui-a"); Receiver b = new Receiver("gui-b")) {
                server = startServer("gui-server");
                Process child = server;
                cleanupHook = new Thread(() -> {
                    a.close(); b.close();
                    try {
                        Files.deleteIfExists(runDir.resolve("test-password.tmp"));
                        stopServer(child);
                    } catch (Exception e) { System.err.println("GUI fixture cleanup failed: " + e.getClass().getSimpleName()); }
                }, "gui-fixture-cleanup");
                Runtime.getRuntime().addShutdownHook(cleanupHook);
                a.connect(); b.connect();
                for (Receiver receiver : List.of(a, b)) receiver.network.addListener(new ClientListener() {
                    public void onAlertReceived(Alert alert) { receiver.network.sendAck(alert.id()); }
                });
                Files.writeString(runDir.resolve("test-password.tmp"), secret);
                Files.writeString(runDir.getParent().resolve("latest-gui-directory.txt"), runDir.toString());
                edu.ec5205.alert.admin.AdminMain.main(new String[0]);
                System.out.println("GUI fixture ready; username integration-admin; port 5050; two automated ACK receivers.");
                while (!Files.exists(runDir.resolve("stop"))) Thread.sleep(100);
            } finally {
                Files.deleteIfExists(runDir.resolve("test-password.tmp"));
                stopServer(server);
                if (cleanupHook != null) Runtime.getRuntime().removeShutdownHook(cleanupHook);
                javax.swing.SwingUtilities.invokeAndWait(() -> {
                    for (var window : Window.getWindows()) window.dispose();
                });
            }
        }
    }

    static Path evidenceRoot(String[] args) throws IOException {
        Path output = Path.of(args.length == 0 ? "runtime/evidence" : args[0]).toAbsolutePath();
        Files.createDirectories(output);
        return output;
    }
    static Path logFile(String name) throws IOException {
        Path directory = Path.of("runtime", "logs", runDir.getFileName().toString()).toAbsolutePath();
        Files.createDirectories(directory);
        return directory.resolve(name);
    }

    static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
    }
    static void waitFor(BooleanSupplier condition, String label) throws Exception {
        long end = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) throw new AssertionError("Timeout: " + label);
            Thread.sleep(10);
        }
    }
    static <T> T take(BlockingQueue<T> queue, String label) throws Exception {
        T value = queue.poll(5, TimeUnit.SECONDS);
        check(value != null, label);
        return value;
    }
    static void pass(String label) { System.out.println("PASS " + label); }
    static Process startServer(String name) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(java, "-cp", cp, ServerProcess.class.getName());
        pb.environment().put("EMERGENCY_ADMIN_USERNAME", "integration-admin");
        pb.environment().put("EMERGENCY_ADMIN_PASSWORD", secret);
        pb.environment().put("EMERGENCY_DB_PATH", runDir.resolve("isolated.db").toString());
        pb.environment().put("EMERGENCY_PORT", Integer.toString(port));
        pb.redirectErrorStream(true).redirectOutput(logFile(name + ".log").toFile());
        Process process = pb.start();
        try {
            waitFor(() -> {
                try { return Files.readString(logFile(name + ".log")).contains("Server is listening"); }
                catch (IOException e) { return false; }
            }, "server startup");
            check(process.isAlive(), "server alive");
            return process;
        } catch (Throwable failure) {
            stopServer(process);
            throw failure;
        }
    }
    static void stopServer(Process process) throws Exception {
        if (process == null || !process.isAlive()) return;
        process.getOutputStream().write(10);
        process.getOutputStream().flush();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("Server did not shut down normally");
        }
        check(process.exitValue() == 0, "normal server exit");
    }
    static final class Receiver implements ClientListener, AutoCloseable {
        final ClientNetworkManager network = new ClientNetworkManager();
        final AlertHistoryManager history = new AlertHistoryManager();
        final BlockingQueue<Alert> alerts = new LinkedBlockingQueue<>();
        final BlockingQueue<AckResult> acks = new LinkedBlockingQueue<>();
        final BlockingQueue<History> histories = new LinkedBlockingQueue<>();
        final BlockingQueue<ErrorMessage> errors = new LinkedBlockingQueue<>();
        final Map<String, Long> receivedAt = new ConcurrentHashMap<>();
        final Path identityFile;
        final ClientIdentity identity;
        Receiver(String name) {
            identityFile = runDir.resolve(name + ".json");
            identity = ClientIdentityStorage.loadOrCreate(identityFile, name);
            network.addListener(this);
        }
        void connect() throws Exception {
            network.connect("127.0.0.1", port, ClientIdentityStorage.loadOrCreate(identityFile, identity.clientName()));
            waitFor(network::isConnected, "receiver registration");
            check(identity.equals(network.getCurrentIdentity()), "retained identity");
        }
        public void onAlertReceived(Alert a) {
            receivedAt.put(a.id(), System.nanoTime());
            history.addAlert(a);
            alerts.add(a);
        }
        public void onAckResult(AckResult result) { acks.add(result); }
        public void onHistoryReceived(History h) { history.mergeHistory(h.alerts()); histories.add(h); }
        public void onError(ErrorMessage e) { errors.add(e); }
        void ack(String id) throws Exception {
            check(network.sendAck(id), "ACK sent by harness, not a human");
            AckResult result = take(acks, "ACK_RESULT");
            check(result.success() && result.alertId().equals(id), "ACK_RESULT matches");
        }
        public void close() { network.disconnect(); }
    }
    static AdminAuthService login(AdminConnection connection) throws Exception {
        connection.connect();
        AdminAuthService auth = new AdminAuthService(connection);
        check(auth.login("integration-admin", secret).successful(), "valid login");
        return auth;
    }
    static void verifyApplicationEntries() throws Exception {
        ProcessBuilder admin = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", cp,
                "edu.ec5205.alert.admin.AdminMain", "--console");
        admin.redirectErrorStream(true).redirectOutput(logFile("admin-entry.log").toFile());
        Process process = admin.start();
        try {
            String input = "127.0.0.1\n" + port + "\nintegration-admin\n" + secret + "\n5\n";
            process.getOutputStream().write(input.getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            check(process.waitFor(8, TimeUnit.SECONDS) && process.exitValue() == 0, "actual admin entry exits normally");
            String output = Files.readString(logFile("admin-entry.log"));
            check(output.contains("Authentication successful") && !output.contains(secret), "actual admin entry login and safe log");
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        ProcessBuilder headless = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", cp,
                "edu.ec5205.alert.client.ClientMain", "--id-file", runDir.resolve("headless-unused.json").toString());
        headless.redirectErrorStream(true).redirectOutput(logFile("client-headless-entry.log").toFile());
        process = headless.start();
        try {
            check(process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0, "receiver graphical entry headless policy");
            check(Files.readString(logFile("client-headless-entry.log")).contains("graphical desktop"), "receiver GUI entry class loads");
            check(!Files.exists(runDir.resolve("headless-unused.json")), "headless GUI entry creates no identity");
        } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        pass("actual admin console entry logs in; receiver GUI entry loads with explicit headless guidance");
    }
    static String broadcast(AlertService service, Receiver a, Receiver b, int index) throws Exception {
        long sent = System.nanoTime();
        var result = service.broadcastAlert("Integration " + index, "Evacuate safely. Unicode: \u2603\nSecond line.", Category.FIRE, Priority.CRITICAL);
        check(result.successful(), "alert creation");
        Alert first = take(a.alerts, "first delivery");
        check(first.id().equals(result.alertId()), "accepted ID equals received ID");
        check(first.title().equals("Integration " + index) && first.message().contains("\u2603\n")
                && first.category() == Category.FIRE && first.priority() == Priority.CRITICAL, "content preserved");
        latency.add(a.receivedAt.get(first.id()) - sent);
        if (b != null) {
            Alert second = take(b.alerts, "second delivery");
            check(first.equals(second), "identical complete alert at both receivers");
            latency.add(b.receivedAt.get(second.id()) - sent);
        }
        return first.id();
    }

    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 17) throw new IllegalStateException("JDK 17 required");
        Path output = evidenceRoot(args);
        Files.createDirectories(output);
        runDir = Files.createTempDirectory(output, "run-");
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        System.out.println("Evidence directory: " + runDir);
        Process server = null;
        Process receiverEntry = null;
        try (Receiver a = new Receiver("receiver-a"); Receiver b = new Receiver("receiver-b");
             AdminConnection admin = new AdminConnection("127.0.0.1", port)) {
            server = startServer("server-first");
            AdminAuthService auth;
            admin.connect();
            auth = new AdminAuthService(admin);
            check(!auth.login("integration-admin", UUID.randomUUID().toString()).successful(), "invalid login rejected");
            check(auth.login("integration-admin", secret).successful(), "valid login after failure");
            pass("invalid/valid real admin service login");
            verifyApplicationEntries();
            // A fresh connection cannot inherit another connection's authorization.
            try (AdminConnection unauth = new AdminConnection("127.0.0.1", port)) {
                unauth.connect();
                var denied = unauth.sendAndAwait(new GetClients(), m -> m instanceof ErrorMessage, TIMEOUT);
                check(((ErrorMessage) denied).code().equals("UNAUTHORIZED"), "per-connection authorization");
            }
            a.connect(); b.connect();
            check(!a.identity.clientId().equals(b.identity.clientId()), "distinct UUIDs");
            waitFor(() -> {
                try { return auth.requestConnectedClients(TIMEOUT).size() == 2; }
                catch (Exception e) { throw new RuntimeException(e); }
            }, "two live clients");
            try (Receiver duplicate = new Receiver("duplicate")) {
                duplicate.network.connect("127.0.0.1", port, a.identity);
                check(take(duplicate.errors, "duplicate registration error").code().equals("DUPLICATE_CLIENT"), "duplicate rejection");
            }
            pass("two receivers listed; duplicate identity and unauthorized connection rejected");
            BlockingQueue<AckStatus> statuses = new LinkedBlockingQueue<>();
            admin.addListener(new MessageListener() {
                public void onMessage(ProtocolMessage message) { if (message instanceof AckStatus s) statuses.add(s); }
            });
            AlertService service = new AlertService(admin, auth);
            String first = broadcast(service, a, b, 0);
            a.ack(first); b.ack(first);
            AckStatus s1 = take(statuses, "first live ACK status");
            AckStatus s2 = take(statuses, "second live ACK status");
            check(s1.alertId().equals(first) && s2.alertId().equals(first)
                    && Set.of(s1.clientId(), s2.clientId()).equals(Set.of(a.identity.clientId(), b.identity.clientId())), "two distinct ACK updates");
            var before = auth.requestAcknowledgements(first, TIMEOUT);
            a.ack(first);
            check(before.size() == 2 && before.equals(auth.requestAcknowledgements(first, TIMEOUT)), "duplicate ACK leaves count and timestamps unchanged");
            check(statuses.poll(250, TimeUnit.MILLISECONDS) == null, "duplicate produces no extra status");
            check(auth.requestHistory(100, TIMEOUT).stream().anyMatch(x -> x.id().equals(first)), "admin history");
            pass("identical broadcast; two harness ACKs; live status; duplicate ACK idempotence; queries");
            for (int i = 1; i <= 10; i++) broadcast(service, a, b, i);
            a.close();
            waitFor(() -> {
                try { return auth.requestConnectedClients(TIMEOUT).size() == 1; }
                catch (Exception e) { throw new RuntimeException(e); }
            }, "disconnect membership");
            String missed = broadcast(service, b, null, 11);
            a.connect();
            check(a.network.requestHistory(100), "history request via real client network");
            History history = take(a.histories, "reconnect history");
            check(history.alerts().stream().anyMatch(x -> x.id().equals(missed)), "missed alert retrieved");
            check(a.history.getAllAlerts().size() == 12, "history merged without duplicate entries");
            a.network.requestHistory(100); take(a.histories, "repeat history");
            check(a.history.getAllAlerts().size() == 12, "repeat history deduplicated by actual manager");
            pass("disconnect isolation; retained identity; explicit history retrieval and manager deduplication");
            admin.close();
            b.ack(missed);
            try (AdminConnection recovered = new AdminConnection("127.0.0.1", port)) {
                var recoveredAuth = login(recovered);
                check(recoveredAuth.requestAcknowledgements(first, TIMEOUT).equals(before), "admin recovers original ACKs");
                check(recoveredAuth.requestAcknowledgements(missed, TIMEOUT).size() == 1, "admin recovers offline ACK");
                pass("fresh admin reconnect recovers stored acknowledgement state");
                // Entry-point smoke test: actual console receiver process, no substitute protocol script.
                ProcessBuilder entry = new ProcessBuilder("powershell.exe", "-NoProfile", "-File",
                        Path.of("scripts", "Start-App.ps1").toAbsolutePath().toString(),
                        "-Application", "Receiver", "-Console", "-ServerHost", "127.0.0.1",
                        "-Port", Integer.toString(port), "-Name", "entry-point",
                        "-IdentityFile", runDir.resolve("entry-client.json").toString());
                entry.directory(Path.of("..").toAbsolutePath().normalize().toFile());
                entry.redirectErrorStream(true).redirectOutput(logFile("client-entry.log").toFile());
                receiverEntry = entry.start();
                waitFor(() -> {
                    try { return Files.readString(logFile("client-entry.log")).contains("Registration successful"); }
                    catch (IOException e) { return false; }
                }, "ClientMain entry point registration");
                var entryAlert = new AlertService(recovered, recoveredAuth).broadcastAlert(
                        "Entry point delivery", "Console process receives this alert.", Category.GENERAL, Priority.LOW);
                check(entryAlert.successful(), "entry-point test alert accepted");
                waitFor(() -> {
                    try { return Files.readString(logFile("client-entry.log")).contains("Title: Entry point delivery"); }
                    catch (IOException e) { return false; }
                }, "ClientMain entry point alert display");
                pass("actual ClientMain process registers and displays an alert (console only)");
                stopServer(server); server = null;
                waitFor(() -> !a.network.isConnected() && !b.network.isConnected() && !recovered.isConnected(), "shutdown socket closure");
                check(receiverEntry.waitFor(5, TimeUnit.SECONDS), "receiver entry exits after server shutdown");
                check(receiverEntry.exitValue() == 0, "receiver entry exit status");
            }
            server = startServer("server-restart");
            try (AdminConnection restarted = new AdminConnection("127.0.0.1", port)) {
                var restartedAuth = login(restarted);
                check(restartedAuth.requestHistory(100, TIMEOUT).size() == 13, "alerts survive restart");
                check(restartedAuth.requestAcknowledgements(first, TIMEOUT).equals(before), "ACK timestamps survive restart");
                check(restartedAuth.requestAcknowledgements(missed, TIMEOUT).size() == 1, "offline ACK survives restart");
                check(restartedAuth.requestConnectedClients(TIMEOUT).isEmpty(), "historical clients not live");
            }
            stopServer(server); server = null;
            try (ServerSocket rebound = new ServerSocket(port)) { check(rebound.isBound(), "port released"); }
            pass("normal restart persistence; shutdown closes connections and releases port");
            for (String log : List.of("server-first.log", "server-restart.log", "client-entry.log", "admin-entry.log", "client-headless-entry.log")) {
                String contents = Files.readString(logFile(log));
                check(!contents.contains(secret) && !contents.contains("did not terminate"), "safe logs and clean shutdown");
            }
            Collections.sort(latency);
            double mean = latency.stream().mapToLong(Long::longValue).average().orElseThrow() / 1e6;
            System.out.printf(Locale.ROOT, "LOCAL_LATENCY samples=%d min_ms=%.3f median_ms=%.3f p95_ms=%.3f max_ms=%.3f mean_ms=%.3f%n",
                    latency.size(), latency.get(0)/1e6, latency.get(latency.size()/2)/1e6,
                    latency.get((int)Math.ceil(latency.size()*.95)-1)/1e6, latency.get(latency.size()-1)/1e6, mean);
            System.out.println("ALL REAL-APPLICATION INTEGRATION CHECKS PASSED; ACKs were harness-generated. GUI/LAN not established by this harness.");
        } finally {
            stopServer(server);
            if (receiverEntry != null && receiverEntry.isAlive()) {
                receiverEntry.destroyForcibly(); receiverEntry.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }
}
