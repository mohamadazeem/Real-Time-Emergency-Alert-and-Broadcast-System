import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.ClientList;
import edu.ec5205.alert.server.storage.ClientRepository;
import edu.ec5205.alert.server.storage.SqliteDatabase;
import java.nio.file.Files;
import java.nio.file.Path;

/** Live-test helper: persisted identities must not be mistaken for currently connected receivers. */
class VerifyLiveClients {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        if (!Files.isRegularFile(path)) throw new AssertionError("Expected an existing database file.");
        ClientList live = (ClientList) new JsonMessageCodec().decode(Files.readString(Path.of(args[1])));
        if (!live.clients().isEmpty()) throw new AssertionError("Expected no connected receivers.");
        ClientRepository clients = new ClientRepository(new SqliteDatabase(path));
        clients.findById(args[2]).orElseThrow();
        if (!clients.findById(args[3]).orElseThrow().name().equals(args[4])) throw new AssertionError("Reconnect name was not persisted.");
        System.out.println("PASS: reopened database retains both identities while the live registry contains neither");
    }
}
