import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.History;
import edu.ec5205.alert.server.storage.AlertRepository;
import edu.ec5205.alert.server.storage.AcknowledgementRepository;
import edu.ec5205.alert.server.storage.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;

/** Separate-JVM live check without exposing new commands or changing production data. */
class VerifyHistory {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        if (!Files.isRegularFile(path)) throw new AssertionError("Expected an existing database file.");
        History history = (History) new JsonMessageCodec().decode(Files.readString(Path.of(args[1])));
        SqliteDatabase database = new SqliteDatabase(path);
        if (!history.alerts().equals(new AlertRepository(database).recent(Integer.parseInt(args[2])))) {
            throw new AssertionError("History differs from the complete newest-N selection in reopened storage.");
        }
        if (args.length > 3 && !args[3].isEmpty()) {
            for (String id : args[3].split(",")) {
                if (!new AcknowledgementRepository(database).findByAlertId(id).isEmpty()) {
                    throw new AssertionError("History retrieval must not acknowledge test alerts.");
                }
            }
        }
        System.out.println("PASS: history matches reopened storage in order and test alerts remain unacknowledged");
    }
}
