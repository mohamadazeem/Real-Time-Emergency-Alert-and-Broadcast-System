import edu.ec5205.alert.admin.network.*;
import edu.ec5205.alert.admin.service.*;
import edu.ec5205.alert.client.ClientMain;
import edu.ec5205.alert.client.model.*;
import edu.ec5205.alert.client.service.ReceiverController;
import edu.ec5205.alert.client.service.ReceiverController.Snapshot;
import edu.ec5205.alert.common.*;
import edu.ec5205.alert.common.Messages.*;
import javax.swing.SwingUtilities;
import java.awt.Window;
import java.net.ServerSocket;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Actual server/admin integration through the same controller used by ReceiverFrame. */
public final class ReceiverWorkflowHarness {
    static <T> T ui(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    static final class Receiver implements AutoCloseable {
        final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
        final List<String> notices = new CopyOnWriteArrayList<>();
        final ReceiverController controller;
        final String name;
        Receiver(String name) throws Exception {
            this.name = name;
            controller = ui(() -> new ReceiverController(IntegrationHarness.runDir.resolve(name + ".json"), state -> {
                IntegrationHarness.check(SwingUtilities.isEventDispatchThread(), "EDT observer");
                snapshot.set(state); notices.add(state.notice());
            }));
        }
        void connect() throws Exception {
            IntegrationHarness.check(ui(() -> controller.connect("127.0.0.1", IntegrationHarness.port, name)), "connect accepted");
            IntegrationHarness.waitFor(() -> snapshot.get() != null && snapshot.get().state() == ConnectionState.CONNECTED && !snapshot.get().busy(), "registered and automatic history complete");
        }
        boolean contains(String id) { return snapshot.get().alerts().stream().anyMatch(a -> a.alert().id().equals(id)); }
        void ack(String id) throws Exception {
            IntegrationHarness.check(ui(() -> controller.acknowledge(id)), "explicit harness ACK accepted");
            IntegrationHarness.waitFor(() -> snapshot.get().alerts().stream().anyMatch(a -> a.alert().id().equals(id) && a.acknowledgement() == AckStatusState.ACKNOWLEDGED), "ACK_RESULT confirmed");
        }
        void history() throws Exception {
            IntegrationHarness.check(ui(controller::requestHistory), "manual history accepted");
            IntegrationHarness.waitFor(() -> !snapshot.get().busy(), "manual history done");
        }
        public void close() throws Exception { ui(() -> { controller.close(); return null; }); }
    }
    public static void main(String[] args) throws Exception {
        if (Runtime.version().feature() != 17) throw new IllegalStateException("Java 17 required");

        IntegrationHarness.runDir = Files.createTempDirectory(IntegrationHarness.evidenceRoot(args), "receiver-workflow-");
        try (ServerSocket reservation = new ServerSocket(0)) { IntegrationHarness.port = reservation.getLocalPort(); }
        System.out.println("Evidence directory: " + IntegrationHarness.runDir);
        Process server = null;
        try (Receiver a = new Receiver("workflow-a"); Receiver b = new Receiver("workflow-b");
             AdminConnection admin = new AdminConnection("127.0.0.1", IntegrationHarness.port)) {
            server = IntegrationHarness.startServer("workflow-server");
            var auth = IntegrationHarness.login(admin);
            var alerts = new AlertService(admin, auth);
            BlockingQueue<AckStatus> statuses = new LinkedBlockingQueue<>();
            admin.addListener(new MessageListener() { public void onMessage(ProtocolMessage message) {
                if (message instanceof AckStatus status) statuses.add(status);
            }});
            a.connect(); b.connect();
            String aId = a.snapshot.get().identity().clientId();
            IntegrationHarness.check(!aId.equals(b.snapshot.get().identity().clientId()), "independent UUID files");
            IntegrationHarness.check(auth.requestConnectedClients(IntegrationHarness.TIMEOUT).size() == 2, "two live receivers");
            IntegrationHarness.pass("two application controllers register; automatic history; distinct retained identities");
            var created = alerts.broadcastAlert("Workflow alert", "Evacuate using the marked exit.", Category.FIRE, Priority.CRITICAL);
            IntegrationHarness.check(created.successful(), "real admin creates alert"); String id = created.alertId();
            IntegrationHarness.waitFor(() -> a.contains(id) && b.contains(id), "both receivers receive alert");
            IntegrationHarness.check(a.snapshot.get().alerts().get(0).alert().equals(b.snapshot.get().alerts().get(0).alert()), "identical alert fields");
            a.ack(id); b.ack(id);
            AckStatus one = IntegrationHarness.take(statuses, "admin ACK one"), two = IntegrationHarness.take(statuses, "admin ACK two");
            IntegrationHarness.check(Set.of(one.clientId(), two.clientId()).size() == 2, "two independent admin statuses");
            IntegrationHarness.check(!ui(() -> a.controller.acknowledge(id)), "duplicate button action rejected");
            IntegrationHarness.check(auth.requestAcknowledgements(id, IntegrationHarness.TIMEOUT).size() == 2, "stored ACK count two");
            a.history(); a.history();
            IntegrationHarness.check(a.snapshot.get().alerts().size() == 1, "live/history duplicate suppression");
            IntegrationHarness.pass("broadcast content; explicit harness ACK -> ACK_RESULT; two live admin updates; duplicate guard; history dedup");
            ui(() -> { a.controller.disconnect(); return null; });
            IntegrationHarness.waitFor(() -> {
                try { return auth.requestConnectedClients(IntegrationHarness.TIMEOUT).size() == 1; }
                catch (Exception e) { throw new RuntimeException(e); }
            }, "isolated disconnect");
            var missed = alerts.broadcastAlert("Missed alert", "Receiver B remains connected.", Category.GENERAL, Priority.LOW);
            IntegrationHarness.check(missed.successful(), "second alert");
            IntegrationHarness.waitFor(() -> b.contains(missed.alertId()), "healthy receiver survives");
            a.connect();
            IntegrationHarness.check(aId.equals(a.snapshot.get().identity().clientId()), "reconnect retains UUID");
            IntegrationHarness.check(a.contains(missed.alertId()) && a.snapshot.get().alerts().size() == 2, "automatic missed-history recovery without duplicates");
            IntegrationHarness.pass("disconnect isolation; manual reconnect retains UUID; automatic missed history");
            for (int i=0; i<7; i++) IntegrationHarness.check(alerts.broadcastAlert("Large " + i, "\ud83d\udea8".repeat(4000), Category.GENERAL, Priority.LOW).successful(), "large alert persisted");
            IntegrationHarness.waitFor(() -> a.snapshot.get().alerts().size() == 9 && b.snapshot.get().alerts().size() == 9, "large live deliveries");
            a.history();
            IntegrationHarness.check(a.notices.stream().anyMatch(n -> n.contains("limit 3)")), "actual server oversized history produces bounded smaller retry");
            IntegrationHarness.check(a.snapshot.get().alerts().size() == 9, "smaller recent history does not erase existing alerts");
            IntegrationHarness.pass("actual oversized HISTORY error retries 100/50/25/12/6/3; preserves displayed alerts");
            IntegrationHarness.stopServer(server); server = null;
            IntegrationHarness.waitFor(() -> a.snapshot.get().state() == ConnectionState.CONNECTION_LOST && b.snapshot.get().state() == ConnectionState.CONNECTION_LOST, "shutdown callback states");
            server = IntegrationHarness.startServer("workflow-restart");
            a.connect(); b.connect();
            try (AdminConnection recovered = new AdminConnection("127.0.0.1", IntegrationHarness.port)) {
                var recoveredAuth = IntegrationHarness.login(recovered);
                IntegrationHarness.check(recoveredAuth.requestAcknowledgements(id, IntegrationHarness.TIMEOUT).size() == 2, "ACKs survive restart");
                IntegrationHarness.check(a.snapshot.get().alerts().size() == 9 && b.snapshot.get().alerts().size() == 9, "reconnect keeps deduped cache");
            }
            IntegrationHarness.pass("normal restart; reconnect both application controllers; stored admin ACK recovery");
        } finally { IntegrationHarness.stopServer(server); }
        try (ServerSocket rebound = new ServerSocket(IntegrationHarness.port)) { IntegrationHarness.check(rebound.isBound(), "port released"); }
        System.out.println("ALL RECEIVER WORKFLOW CHECKS PASSED. ACK actions above were invoked by a harness, not a person. Localhost only.");
    }

    /** Real UI fixture: no automatic registration, alert generation or acknowledgements. */
    public static final class GuiFixture {
        public static void main(String[] args) throws Exception {
            IntegrationHarness.runDir = Files.createTempDirectory(IntegrationHarness.evidenceRoot(args), "receiver-gui-");
            Files.createDirectories(IntegrationHarness.runDir); IntegrationHarness.port = 5050;
            Process server = IntegrationHarness.startServer("receiver-gui-server");
            Thread hook = new Thread(() -> { try {
                Files.deleteIfExists(IntegrationHarness.runDir.resolve("test-password.tmp")); IntegrationHarness.stopServer(server);
            } catch (Exception e) { System.err.println("Fixture cleanup failed."); } });
            Runtime.getRuntime().addShutdownHook(hook);
            try {
                Files.writeString(IntegrationHarness.runDir.resolve("test-password.tmp"), IntegrationHarness.secret);
                Files.writeString(IntegrationHarness.runDir.getParent().resolve("latest-receiver-gui-directory.txt"), IntegrationHarness.runDir.toString());
                edu.ec5205.alert.admin.AdminMain.main(new String[0]);
                for (String name : List.of("Receiver A", "Receiver B")) ClientMain.main(new String[]{"--name", name,
                        "--id-file", IntegrationHarness.runDir.resolve(name+".json").toString()});
                System.out.println("Real admin and two real receiver windows ready. No automatic ACKs. Username integration-admin; port 5050.");
                while (!Files.exists(IntegrationHarness.runDir.resolve("stop"))) Thread.sleep(100);
            } finally {
                SwingUtilities.invokeAndWait(() -> { for (Window window : Window.getWindows()) window.dispose(); });
                Files.deleteIfExists(IntegrationHarness.runDir.resolve("test-password.tmp"));
                IntegrationHarness.stopServer(server); Runtime.getRuntime().removeShutdownHook(hook);
            }
        }
    }
}
