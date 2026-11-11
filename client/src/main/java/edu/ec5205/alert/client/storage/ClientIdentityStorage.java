package edu.ec5205.alert.client.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.ec5205.alert.client.model.ClientIdentity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * Handles persistent storage of client identity (UUID and display name).
 */
public final class ClientIdentityStorage {
    public static final String DEFAULT_FILE_NAME = "client-identity.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ClientIdentityStorage() {}

    public static Path defaultPath() {
        return Paths.get(DEFAULT_FILE_NAME);
    }

    /**
     * Loads identity from storage file, or generates and saves a new identity if missing or corrupted.
     *
     * @param storagePath Path to identity storage file
     * @param preferredName Preferred client name if none saved or if creating new
     * @return ClientIdentity with stable UUID
     */
    public static ClientIdentity loadOrCreate(Path storagePath, String preferredName) {
        String effectiveName = (preferredName != null && !preferredName.isBlank()) 
                ? preferredName.trim() 
                : "Receiver-" + UUID.randomUUID().toString().substring(0, 8);

        if (Files.exists(storagePath)) {
            try (Reader reader = Files.newBufferedReader(storagePath, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                if (json.has("clientId")) {
                    String rawId = json.get("clientId").getAsString();
                    UUID uuid = UUID.fromString(rawId); // Validate UUID format
                    String loadedName = json.has("clientName") ? json.get("clientName").getAsString().trim() : "";
                    
                    // If preferred name was explicitly given and differs from loaded name, update name
                    String nameToUse = (preferredName != null && !preferredName.isBlank()) ? preferredName.trim() : loadedName;
                    if (nameToUse.isEmpty()) {
                        nameToUse = "Receiver-" + uuid.toString().substring(0, 8);
                    }

                    ClientIdentity identity = new ClientIdentity(uuid.toString(), nameToUse);
                    if (!nameToUse.equals(loadedName)) {
                        save(storagePath, identity);
                    }
                    return identity;
                }
            } catch (Exception e) {
                System.err.println("Warning: Corrupted identity file at " + storagePath + ". Generating a new identity: " + e.getMessage());
            }
        }

        // Generate new identity
        String newId = UUID.randomUUID().toString();
        ClientIdentity identity = new ClientIdentity(newId, effectiveName);
        save(storagePath, identity);
        return identity;
    }

    /**
     * Saves identity to the designated file.
     */
    public static void save(Path storagePath, ClientIdentity identity) {
        try {
            if (storagePath.getParent() != null) {
                Files.createDirectories(storagePath.getParent());
            }
            JsonObject json = new JsonObject();
            json.addProperty("clientId", identity.clientId());
            json.addProperty("clientName", identity.clientName());
            try (Writer writer = Files.newBufferedWriter(storagePath, StandardCharsets.UTF_8)) {
                GSON.toJson(json, writer);
            }
        } catch (IOException e) {
            System.err.println("Error saving client identity to " + storagePath + ": " + e.getMessage());
        }
    }
}

