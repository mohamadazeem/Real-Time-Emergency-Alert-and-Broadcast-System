import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.AckList;
import edu.ec5205.alert.common.Messages.AckStatus;
import edu.ec5205.alert.common.Messages.Acknowledgement;
import edu.ec5205.alert.server.storage.AlertRepository;
import edu.ec5205.alert.server.storage.AcknowledgementRepository;
import edu.ec5205.alert.server.storage.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;

/** Source-file live helper; compares original JSON timestamps without PowerShell date rounding. */
class VerifyAckList {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        if (!Files.isRegularFile(path)) throw new AssertionError("Expected an existing database file.");
        JsonMessageCodec codec = new JsonMessageCodec();
        AckList list = (AckList) codec.decode(Files.readString(Path.of(args[1])));
        SqliteDatabase database = new SqliteDatabase(path);
        new AlertRepository(database).findById(list.alertId()).orElseThrow();
        var stored = new AcknowledgementRepository(database).findByAlertId(list.alertId());
        if (stored.size() != Integer.parseInt(args[3]) || !stored.equals(list.acknowledgements())) {
            throw new AssertionError("ACK_LIST differs from the complete ordered list in reopened storage.");
        }
        if (Files.isRegularFile(Path.of(args[2]))) {
            AckStatus first = (AckStatus) codec.decode(Files.readString(Path.of(args[2])));
            if (!first.alertId().equals(list.alertId()) ||
                    !stored.contains(new Acknowledgement(first.clientId(), first.acknowledgedAt()))) {
                throw new AssertionError("Original committed acknowledgement timestamp changed.");
            }
        }
        System.out.println("PASS: ACK_LIST matches reopened storage, ordering, count, and original timestamps");
    }
}
