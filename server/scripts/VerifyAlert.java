import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.CreateAlert;
import edu.ec5205.alert.common.Messages.AlertMessage;
import edu.ec5205.alert.server.storage.AlertRepository;
import edu.ec5205.alert.server.storage.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Source-file test helper invoked by Test-AlertCreation.ps1, outside production sources. */
class VerifyAlert {
    public static void main(String[] args) throws Exception {
        Path databasePath = Path.of(args[0]);
        if (!Files.isRegularFile(databasePath)) throw new AssertionError("Expected an existing database file.");
        CreateAlert expected = (CreateAlert) new JsonMessageCodec().decode(Files.readString(Path.of(args[2])));
        var saved = new AlertRepository(new SqliteDatabase(databasePath)).findById(args[1]).orElseThrow();
        if (!saved.title().equals(expected.title()) || !saved.message().equals(expected.message())
                || saved.category() != expected.category() || saved.priority() != expected.priority()) {
            throw new AssertionError("Saved alert fields differ from the accepted request.");
        }
        if (UUID.fromString(saved.id()).version() != 4 || saved.id().equals(expected.requestId())
                || !saved.createdAt().endsWith("Z")) {
            throw new AssertionError("Expected a server-generated UUID and UTC timestamp.");
        }
        Instant.parse(saved.createdAt());
        if (args.length > 3) {
            AlertMessage delivered = (AlertMessage) new JsonMessageCodec().decode(Files.readString(Path.of(args[3])));
            if (!saved.equals(delivered.alert())) throw new AssertionError("Broadcast differs from the exact stored alert.");
        }
        System.out.println("PASS: accepted alert and its fields survived reopening the database");
    }
}
