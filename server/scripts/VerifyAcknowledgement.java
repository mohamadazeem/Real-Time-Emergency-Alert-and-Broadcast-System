import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.AckStatus;
import edu.ec5205.alert.common.Messages.Acknowledgement;
import edu.ec5205.alert.server.storage.AcknowledgementRepository;
import edu.ec5205.alert.server.storage.ClientRepository;
import edu.ec5205.alert.server.storage.AlertRepository;
import edu.ec5205.alert.server.storage.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Live-test helper: reopen the database in a separate JVM, without adding query endpoints. */
class VerifyAcknowledgement {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        if (!Files.isRegularFile(path)) throw new AssertionError("Expected an existing database file.");
        AckStatus status = (AckStatus) new JsonMessageCodec().decode(Files.readString(Path.of(args[1])));
        if (!status.clientId().equals(args[2])) throw new AssertionError("Incorrect connection identity.");
        Instant.parse(status.acknowledgedAt());
        SqliteDatabase database = new SqliteDatabase(path);
        var saved = new AcknowledgementRepository(database).findByAlertId(status.alertId());
        if (!saved.equals(List.of(new Acknowledgement(status.clientId(), status.acknowledgedAt())))) {
            throw new AssertionError("Expected one row matching the original ACK_STATUS timestamp and client.");
        }
        var client = new ClientRepository(database).findById(status.clientId()).orElseThrow();
        if (!client.name().equals(args[3])) throw new AssertionError("Registration name was not persisted.");
        new AlertRepository(database).findById(status.alertId()).orElseThrow();
        System.out.println("PASS: reopened database contains the registered client, alert, and single original acknowledgement");
    }
}
