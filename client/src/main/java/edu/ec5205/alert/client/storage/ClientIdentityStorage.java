package edu.ec5205.alert.client.storage;

import com.google.gson.*;
import edu.ec5205.alert.client.model.ClientIdentity;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** Retains identity atomically; unreadable/corrupt files are preserved for recovery. */
public final class ClientIdentityStorage {
    public static final String DEFAULT_FILE_NAME = "client-identity.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private ClientIdentityStorage() { }
    public static Path defaultPath() { return Path.of("runtime", "data", DEFAULT_FILE_NAME); }
    public static ClientIdentity loadOrCreate(Path path, String preferredName) {
        String name = preferredName == null || preferredName.isBlank() ? null : preferredName.trim();
        if (Files.exists(path)) {
            ClientIdentity loaded;
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                String rawId = json.get("clientId").getAsString();
                String id = UUID.fromString(rawId).toString();
                if (!id.equalsIgnoreCase(rawId)) throw new IllegalArgumentException();
                String oldName = json.has("clientName") ? json.get("clientName").getAsString().trim() : "";
                loaded = new ClientIdentity(id, oldName.isBlank() ? "Receiver-" + id.substring(0, 8) : oldName);
            } catch (IOException e) {
                throw new UncheckedIOException("Unable to read identity file.", e);
            } catch (RuntimeException e) {
                throw new IllegalStateException("Identity file is invalid; restore it or choose a new identity path. File preserved.", e);
            }
            ClientIdentity result = name == null ? loaded : loaded.withName(name);
            if (!result.equals(loaded)) save(path, result);
            return result;
        }
        String id = UUID.randomUUID().toString();
        ClientIdentity result = new ClientIdentity(id, name == null ? "Receiver-" + id.substring(0, 8) : name);
        save(path, result);
        return result;
    }
    public static void save(Path path, ClientIdentity identity) {
        Path absolute = path.toAbsolutePath();
        Path temporary = null;
        try {
            Files.createDirectories(absolute.getParent());
            temporary = Files.createTempFile(absolute.getParent(), ".receiver-identity-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(identity, writer);
            }
            try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING); }
        } catch (IOException e) { throw new UncheckedIOException("Unable to save stable receiver identity.", e); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }
}
