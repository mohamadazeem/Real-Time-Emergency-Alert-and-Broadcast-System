package edu.ec5205.alert.client.storage;

import edu.ec5205.alert.client.model.ClientIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ClientIdentityStorageTest {

    @Test
    void generatesAndPersistsNewUuidWhenFileDoesNotExist(@TempDir Path tempDir) {
        Path identityFile = tempDir.resolve("new-client.json");
        assertFalse(Files.exists(identityFile));

        ClientIdentity identity = ClientIdentityStorage.loadOrCreate(identityFile, "Receiver 01");
        assertNotNull(identity);
        assertNotNull(identity.clientId());
        assertDoesNotThrow(() -> UUID.fromString(identity.clientId()));
        assertEquals("Receiver 01", identity.clientName());
        assertTrue(Files.exists(identityFile));
    }

    @Test
    void reusesSameUuidOnRestart(@TempDir Path tempDir) {
        Path identityFile = tempDir.resolve("existing-client.json");
        ClientIdentity firstLaunch = ClientIdentityStorage.loadOrCreate(identityFile, "Receiver 01");
        String originalUuid = firstLaunch.clientId();

        // Simulate restart
        ClientIdentity secondLaunch = ClientIdentityStorage.loadOrCreate(identityFile, "Receiver 01");
        assertEquals(originalUuid, secondLaunch.clientId());
        assertEquals("Receiver 01", secondLaunch.clientName());
    }

    @Test
    void allowsNameChangeWithoutChangingUuid(@TempDir Path tempDir) {
        Path identityFile = tempDir.resolve("client-name-change.json");
        ClientIdentity initial = ClientIdentityStorage.loadOrCreate(identityFile, "Initial Name");
        String initialUuid = initial.clientId();

        // Restart with new preferred name
        ClientIdentity updated = ClientIdentityStorage.loadOrCreate(identityFile, "Updated Display Name");
        assertEquals(initialUuid, updated.clientId(), "UUID must remain unchanged when name changes");
        assertEquals("Updated Display Name", updated.clientName());

        // Verify persisted state matches
        ClientIdentity reloaded = ClientIdentityStorage.loadOrCreate(identityFile, null);
        assertEquals(initialUuid, reloaded.clientId());
        assertEquals("Updated Display Name", reloaded.clientName());
    }

    @Test
    void recoversGracefullyFromCorruptedIdentityFile(@TempDir Path tempDir) throws IOException {
        Path identityFile = tempDir.resolve("corrupted.json");
        Files.writeString(identityFile, "{ corrupted invalid json content !!!");

        ClientIdentity recovered = ClientIdentityStorage.loadOrCreate(identityFile, "Recovered Client");
        assertNotNull(recovered);
        assertNotNull(recovered.clientId());
        assertDoesNotThrow(() -> UUID.fromString(recovered.clientId()));
        assertEquals("Recovered Client", recovered.clientName());
    }
}

