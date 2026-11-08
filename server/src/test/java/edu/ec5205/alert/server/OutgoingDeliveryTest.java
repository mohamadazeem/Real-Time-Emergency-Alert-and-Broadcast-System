package edu.ec5205.alert.server;

import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.JsonMessageCodec;
import edu.ec5205.alert.common.Messages.*;
import edu.ec5205.alert.common.Priority;
import edu.ec5205.alert.common.ProtocolMessage;
import edu.ec5205.alert.server.storage.AlertRepository;
import edu.ec5205.alert.server.storage.SqliteDatabase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class OutgoingDeliveryTest {
    private static final String FIRST = "123e4567-e89b-12d3-a456-426614174000";
    private static final String SECOND = "223e4567-e89b-12d3-a456-426614174000";
    private final JsonMessageCodec codec = new JsonMessageCodec();
    @TempDir Path directory;

    @Test
    void fullQueueDisconnectsBlockedReceiverWithoutBlockingPersistenceOrHealthyDelivery() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry();
        BlockingOutput blocked = new BlockingOutput();
        TrackingOutput healthy = new TrackingOutput();
        AtomicInteger closed = new AtomicInteger();
        try (ClientConnection slow = new ClientConnection(new TestSocket(blocked), 1, connection -> {
                 registry.unregister(connection); closed.incrementAndGet();
             });
             ClientConnection fast = new ClientConnection(new TestSocket(healthy), 32, registry::unregister)) {
            assertTrue(registry.register(FIRST, slow));
            slow.startWriter();
            assertTrue(blocked.entered.await(3, TimeUnit.SECONDS)); // REGISTERED is stuck in the socket write.
            assertTrue(registry.register(SECOND, fast));
            fast.startWriter();
            AlertRepository repository = new AlertRepository(new SqliteDatabase(directory.resolve("slow-receiver.db")));
            AlertCreationService service = new AlertCreationService(repository, registry);
            AlertAccepted first = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> service.create(request()));
            assertFalse(slow.isClosed()); // Its one queue slot now holds the first ALERT.
            AlertAccepted second = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> service.create(request()));
            assertTrue(slow.isClosed()); // The second ALERT cannot be queued.
            assertNull(registry.find(FIRST));
            assertEquals(1, closed.get());
            assertTrue(slow.awaitWriter(3000));
            assertFalse(fast.isClosed());
            fast.finish();
            ByteArrayInputStream frames = new ByteArrayInputStream(healthy.toByteArray());
            assertEquals(new Registered(SECOND), codec.read(frames));
            assertEquals(new AlertMessage(repository.findById(first.alertId()).orElseThrow()), codec.read(frames));
            assertEquals(new AlertMessage(repository.findById(second.alertId()).orElseThrow()), codec.read(frames));
            assertNull(codec.read(frames));
            assertEquals(2, repository.recent(100).size());
        }
    }

    @Test
    void concurrentProducersUseOneWriterAndProduceOnlyCompleteJsonFrames() throws Exception {
        TrackingOutput output = new TrackingOutput();
        ExecutorService producers = Executors.newFixedThreadPool(2);
        try (ClientConnection connection = new ClientConnection(new TestSocket(output), 256, ignored -> {})) {
            connection.startWriter();
            Future<?> first = producers.submit(() -> sendBatch(connection, "first"));
            Future<?> second = producers.submit(() -> sendBatch(connection, "second"));
            first.get(3, TimeUnit.SECONDS);
            second.get(3, TimeUnit.SECONDS);
            connection.finish();
            assertTrue(connection.awaitWriter(100));
            ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
            Set<String> messages = new HashSet<>();
            ProtocolMessage message;
            while ((message = codec.read(frames)) != null) messages.add(assertInstanceOf(ErrorMessage.class, message).message());
            assertEquals(200, messages.size());
            assertEquals(1, output.writerThreads.size());
            assertTrue(output.closed);
        } finally {
            producers.shutdownNow();
            assertTrue(producers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void registrationRacingBroadcastAlwaysPlacesRegisteredBeforeAlerts() throws Exception {
        ExecutorService racers = Executors.newFixedThreadPool(2);
        try {
            for (int index = 0; index < 30; index++) {
                ConnectionRegistry registry = new ConnectionRegistry();
                TrackingOutput output = new TrackingOutput();
                Alert alert = new Alert(UUID.randomUUID().toString(), "2026-11-09T00:00:00Z", "Title", "Body", Category.FIRE, Priority.LOW);
                try (ClientConnection connection = new ClientConnection(new TestSocket(output), 8, registry::unregister)) {
                    CountDownLatch go = new CountDownLatch(1);
                    Future<Boolean> registration = racers.submit(() -> { go.await(); return registry.register(FIRST, connection); });
                    Future<?> broadcast = racers.submit(() -> { go.await(); registry.broadcast(alert); return null; });
                    go.countDown();
                    assertTrue(registration.get(3, TimeUnit.SECONDS));
                    broadcast.get(3, TimeUnit.SECONDS);
                    registry.broadcast(alert); // Ensure at least one broadcast after membership publication.
                    connection.startWriter();
                    connection.finish();
                    ByteArrayInputStream frames = new ByteArrayInputStream(output.toByteArray());
                    assertEquals(new Registered(FIRST), codec.read(frames));
                    assertEquals(new AlertMessage(alert), codec.read(frames));
                    ProtocolMessage extra = codec.read(frames);
                    if (extra != null) assertEquals(new AlertMessage(alert), extra);
                    assertNull(codec.read(frames));
                }
            }
        } finally {
            racers.shutdownNow();
            assertTrue(racers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownClosesIdleAndBlockedWritersAndRemovesOwnedRegistrations() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry();
        BlockingOutput blocked = new BlockingOutput();
        TrackingOutput idle = new TrackingOutput();
        try (ClientConnection first = new ClientConnection(new TestSocket(blocked), 4, registry::unregister);
             ClientConnection second = new ClientConnection(new TestSocket(idle), 4, registry::unregister)) {
            assertTrue(registry.register(FIRST, first));
            assertTrue(registry.register(SECOND, second));
            first.startWriter(); second.startWriter();
            assertTrue(blocked.entered.await(3, TimeUnit.SECONDS));
            first.close(); second.close();
            assertTrue(first.awaitWriter(3000));
            assertTrue(second.awaitWriter(3000));
            assertNull(registry.find(FIRST));
            assertNull(registry.find(SECOND));
            assertTrue(blocked.closed);
            assertTrue(idle.closed);
            assertFalse(first.send(request()));
        }
    }

    @Test
    void writerFailureUnregistersReceiverWithoutWaitingForItsReader() throws Exception {
        ConnectionRegistry registry = new ConnectionRegistry();
        OutputStream broken = new OutputStream() {
            @Override public void write(int value) throws IOException { throw new IOException("Disconnected"); }
        };
        try (ClientConnection connection = new ClientConnection(new TestSocket(broken), 4, registry::unregister)) {
            assertTrue(registry.register(FIRST, connection));
            connection.startWriter();
            assertTrue(connection.awaitWriter(3000));
            assertTrue(connection.isClosed());
            assertNull(registry.find(FIRST));
        }
    }

    private static void sendBatch(ClientConnection connection, String prefix) {
        for (int index = 0; index < 100; index++) {
            assertTrue(connection.send(new ErrorMessage("INVALID_MESSAGE", prefix + index, null)));
        }
    }
    private static CreateAlert request() {
        return new CreateAlert(UUID.randomUUID().toString(), "Title", "Body", Category.GENERAL, Priority.LOW);
    }
    private static final class TestSocket extends Socket {
        private final OutputStream output;
        TestSocket(OutputStream output) { this.output = output; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public synchronized void close() throws IOException { output.close(); super.close(); }
    }
    private static final class TrackingOutput extends ByteArrayOutputStream {
        final Set<Long> writerThreads = new HashSet<>();
        volatile boolean closed;
        @Override public synchronized void write(byte[] bytes, int offset, int length) {
            writerThreads.add(Thread.currentThread().getId()); super.write(bytes, offset, length);
        }
        @Override public synchronized void write(int value) {
            writerThreads.add(Thread.currentThread().getId()); super.write(value);
        }
        @Override public void close() { closed = true; }
    }
    private static final class BlockingOutput extends OutputStream {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch released = new CountDownLatch(1);
        volatile boolean closed;
        @Override public void write(int value) throws IOException {
            entered.countDown();
            try { released.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            throw new IOException("Closed blocked writer");
        }
        @Override public void close() { closed = true; released.countDown(); }
    }
}
