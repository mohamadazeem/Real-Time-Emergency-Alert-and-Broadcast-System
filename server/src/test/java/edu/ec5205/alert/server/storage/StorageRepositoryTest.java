package edu.ec5205.alert.server.storage;

import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.Messages.Acknowledgement;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Messages.ClientInfo;
import edu.ec5205.alert.common.Priority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class StorageRepositoryTest {
    private static final String CLIENT = "123e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_CLIENT = "223e4567-e89b-12d3-a456-426614174000";
    private static final String ALERT = "323e4567-e89b-12d3-a456-426614174000";
    private static final String OTHER_ALERT = "423e4567-e89b-12d3-a456-426614174000";
    private static final String TIME = "2026-11-09T09:30:00Z";
    @TempDir Path directory;
    private Path path;
    private SqliteDatabase database;
    private ClientRepository clients;
    private AlertRepository alerts;
    private AcknowledgementRepository acknowledgements;

    @BeforeEach
    void open() throws Exception {
        path = directory.resolve("data with spaces සිංහල").resolve("test.sqlite3");
        database = new SqliteDatabase(path);
        clients = new ClientRepository(database);
        alerts = new AlertRepository(database);
        acknowledgements = new AcknowledgementRepository(database);
    }

    @Test
    void dataAndUnicodeSurviveReopeningAndSchemaInitializationIsIdempotent() throws Exception {
        ClientInfo client = new ClientInfo(CLIENT, "පුස්තකාලය café 🚨 O'Brien'); DROP TABLE alerts; --");
        Alert alert = new Alert(ALERT, TIME, "අනතුරු café 🚨", "First line\nදෙවන පේළිය 'quoted'", Category.FIRE, Priority.CRITICAL);
        Acknowledgement acknowledgement = new Acknowledgement(CLIENT, "2026-11-09T09:31:00.123456789+00:00");
        clients.save(client);
        alerts.insert(alert);
        assertTrue(acknowledgements.save(ALERT, acknowledgement));

        SqliteDatabase reopened = new SqliteDatabase(path);
        assertEquals(client, new ClientRepository(reopened).findById(CLIENT).orElseThrow());
        assertEquals(alert, new AlertRepository(reopened).findById(ALERT).orElseThrow());
        assertEquals(List.of(acknowledgement), new AcknowledgementRepository(reopened).findByAlertId(ALERT));
        assertEquals(List.of(alert), new AlertRepository(reopened).recent(100));
    }

    @Test
    void updatingClientNamePreservesAcknowledgementsAndOneIdentity() throws Exception {
        clients.save(new ClientInfo(CLIENT, "Old display name"));
        alerts.insert(alert(ALERT, TIME));
        Acknowledgement ack = new Acknowledgement(CLIENT, TIME);
        acknowledgements.save(ALERT, ack);
        clients.save(new ClientInfo(CLIENT.toUpperCase(), "Updated සිංහල name"));
        assertEquals(new ClientInfo(CLIENT, "Updated සිංහල name"), clients.findById(CLIENT.toUpperCase()).orElseThrow());
        assertEquals(List.of(ack), acknowledgements.findByAlertId(ALERT));
    }

    @Test
    void historyChoosesNewestSubsetThenOrdersOldestFirstAtNanosecondPrecision() throws Exception {
        Alert first = alert("00000000-0000-0000-0000-000000000001", "2026-11-09T09:30:00Z");
        Alert second = alert("00000000-0000-0000-0000-000000000002", "2026-11-09T09:30:00.1Z");
        Alert third = alert("00000000-0000-0000-0000-000000000003", "2026-11-09T09:30:00.100000001+00:00");
        Alert fourth = alert("00000000-0000-0000-0000-000000000004", "2026-11-09T09:30:01Z");
        for (Alert value : List.of(fourth, second, first, third)) alerts.insert(value);
        assertEquals(List.of(third, fourth), alerts.recent(2));
        assertEquals(List.of(fourth), alerts.recent(1));
        assertEquals(List.of(first, second, third, fourth), alerts.recent(100));
    }

    @Test
    void equalInstantsHaveDeterministicIdTieBreakingWithoutChangingTimestampText() throws Exception {
        Alert first = alert(ALERT, "2026-11-09T09:30:00.1Z");
        Alert second = alert(OTHER_ALERT, "2026-11-09T09:30:00.100000000+00:00");
        alerts.insert(second);
        alerts.insert(first);
        assertEquals(List.of(first, second), alerts.recent(2));
        assertEquals(List.of(second), alerts.recent(1));
    }

    @Test
    void historyBoundsAreEnforcedAndMaximumNeverReturnsMoreThan100() throws Exception {
        for (int value : List.of(-1, 0, 101, Integer.MAX_VALUE)) {
            assertThrows(IllegalArgumentException.class, () -> alerts.recent(value));
        }
        for (int index = 0; index < 101; index++) {
            alerts.insert(alert(new UUID(0, index).toString(), TIME));
        }
        assertEquals(100, alerts.recent(100).size());
        assertEquals(new UUID(0, 1).toString(), alerts.recent(100).get(0).id());
    }

    @Test
    void duplicateAcknowledgementsKeepFirstTimestampAndAreUniquePerPair() throws Exception {
        clients.save(new ClientInfo(CLIENT, "First"));
        clients.save(new ClientInfo(OTHER_CLIENT, "Second"));
        alerts.insert(alert(ALERT, TIME));
        alerts.insert(alert(OTHER_ALERT, TIME));
        Acknowledgement first = new Acknowledgement(CLIENT, TIME);
        assertTrue(acknowledgements.save(ALERT, first));
        assertFalse(acknowledgements.save(ALERT.toUpperCase(), new Acknowledgement(CLIENT.toUpperCase(), "2026-11-09T10:00:00Z")));
        Acknowledgement second = new Acknowledgement(OTHER_CLIENT, "2026-11-09T09:31:00Z");
        assertTrue(acknowledgements.save(ALERT, second));
        assertTrue(acknowledgements.save(OTHER_ALERT, first));
        assertEquals(List.of(first, second), acknowledgements.findByAlertId(ALERT));
        assertEquals(List.of(first), acknowledgements.findByAlertId(OTHER_ALERT));
    }

    @Test
    void foreignKeysRejectMissingParentsOnFreshConnectionsAndDoNotPoisonLaterWrites() throws Exception {
        clients.save(new ClientInfo(CLIENT, "Known client"));
        alerts.insert(alert(ALERT, TIME));
        assertThrows(SQLException.class, () -> acknowledgements.save(OTHER_ALERT, new Acknowledgement(CLIENT, TIME)));
        assertThrows(SQLException.class, () -> acknowledgements.save(ALERT, new Acknowledgement(OTHER_CLIENT, TIME)));
        assertTrue(acknowledgements.findByAlertId(ALERT).isEmpty());
        assertTrue(acknowledgements.save(ALERT, new Acknowledgement(CLIENT, TIME)));
    }

    @Test
    void referencedParentsCannotBeDeletedAndEveryConnectionEnablesForeignKeys() throws Exception {
        clients.save(new ClientInfo(CLIENT, "Known client"));
        alerts.insert(alert(ALERT, TIME));
        acknowledgements.save(ALERT, new Acknowledgement(CLIENT, TIME));
        for (int index = 0; index < 3; index++) {
            Connection used = database.transaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement("PRAGMA foreign_keys");
                     ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals(1, result.getInt(1));
                }
                return connection;
            });
            assertTrue(used.isClosed(), "No connection may leak outside its transaction");
        }
        assertThrows(SQLException.class, () -> database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM clients WHERE clientId = ?")) {
                statement.setString(1, CLIENT);
                return statement.executeUpdate();
            }
        }));
        assertThrows(SQLException.class, () -> database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM alerts WHERE id = ?")) {
                statement.setString(1, ALERT);
                return statement.executeUpdate();
            }
        }));
        assertTrue(clients.findById(CLIENT).isPresent());
        assertTrue(alerts.findById(ALERT).isPresent());
    }

    @Test
    void failedTransactionRollsBackAllItsChanges() throws Exception {
        clients.save(new ClientInfo(CLIENT, "Original"));
        assertThrows(SQLException.class, () -> database.transaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement("INSERT INTO clients(clientId, name) VALUES (?, ?)")) {
                statement.setString(1, OTHER_CLIENT);
                statement.setString(2, "Must roll back");
                statement.executeUpdate();
                statement.setString(1, CLIENT); // Duplicate primary key fails after the first insert.
                return statement.executeUpdate();
            }
        }));
        assertTrue(clients.findById(OTHER_CLIENT).isEmpty());
        assertEquals("Original", clients.findById(CLIENT).orElseThrow().name());
    }

    @Test
    void concurrentRepositoriesShareTheLockAndDuplicateAcksHaveOneWinner() throws Exception {
        alerts.insert(alert(ALERT, TIME));
        ExecutorService workers = Executors.newFixedThreadPool(4);
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try {
            for (int index = 0; index < 4; index++) {
                results.add(workers.submit(() -> {
                    ready.countDown();
                    go.await();
                    new ClientRepository(database).save(new ClientInfo(CLIENT, "Concurrent name"));
                    return new AcknowledgementRepository(database).save(ALERT, new Acknowledgement(CLIENT, TIME));
                }));
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            go.countDown();
            int inserted = 0;
            for (Future<Boolean> result : results) if (result.get(5, TimeUnit.SECONDS)) inserted++;
            assertEquals(1, inserted);
            assertEquals(1, acknowledgements.findByAlertId(ALERT).size());
        } finally {
            go.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void duplicateAlertIdsAreRejectedWithoutReplacingOriginal() throws Exception {
        Alert original = alert(ALERT, TIME);
        alerts.insert(original);
        assertThrows(SQLException.class, () -> alerts.insert(alert(ALERT.toUpperCase(), "2026-11-09T10:00:00Z")));
        assertEquals(original, alerts.findById(ALERT.toUpperCase()).orElseThrow());
    }

    @Test
    void unknownRecordsReturnEmptyResults() throws Exception {
        assertTrue(clients.findById(CLIENT).isEmpty());
        assertTrue(alerts.findById(ALERT).isEmpty());
        assertTrue(alerts.recent(1).isEmpty());
        assertTrue(acknowledgements.findByAlertId(ALERT).isEmpty());
    }

    @Test
    void pathConfigurationHasLocalDefaultAndRejectsBlankOrDirectory() throws Exception {
        assertEquals(Path.of("data/emergency-alerts.db"), SqliteDatabase.pathFromEnvironment(Map.of()));
        assertEquals(path, SqliteDatabase.pathFromEnvironment(Map.of("EMERGENCY_DB_PATH", path.toString())));
        assertThrows(IllegalArgumentException.class, () -> SqliteDatabase.pathFromEnvironment(Map.of("EMERGENCY_DB_PATH", "  ")));
        assertThrows(java.io.IOException.class, () -> new SqliteDatabase(directory));
        assertTrue(Files.isRegularFile(path));
    }

    @Test
    void invalidModelValuesFailWithoutPartialRows() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> clients.save(new ClientInfo("not-a-uuid", "Name")));
        assertThrows(IllegalArgumentException.class, () -> alerts.insert(new Alert(ALERT, TIME, " \t", "Body", Category.FIRE, Priority.LOW)));
        assertThrows(IllegalArgumentException.class, () -> alerts.insert(alert(ALERT, "2026-11-09T09:30:00+05:30")));
        assertTrue(alerts.recent(100).isEmpty());
    }

    private static Alert alert(String id, String time) {
        return new Alert(id, time, "Test alert", "Test message", Category.GENERAL, Priority.MEDIUM);
    }
}
