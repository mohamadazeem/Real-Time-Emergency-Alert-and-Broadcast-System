package edu.ec5205.alert.server;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class ServerLimitsTest {
    @Test
    void serverPortIsConfigurableAndValidated() {
        assertEquals(5050, ServerMain.portFromEnvironment(Map.of()));
        assertEquals(15050, ServerMain.portFromEnvironment(Map.of("EMERGENCY_PORT", "15050")));
        for (String value : List.of("0", "65536", "", "abc")) {
            assertThrows(IllegalArgumentException.class,
                    () -> ServerMain.portFromEnvironment(Map.of("EMERGENCY_PORT", value)));
        }
    }
    @Test
    void defaultsAccommodateAdminAndTwoDemoReceiversAndConfigurationIsBounded() {
        assertEquals(new ServerLimits(8, 32), ServerLimits.fromEnvironment(Map.of()));
        assertEquals(new ServerLimits(3, 2), ServerLimits.fromEnvironment(Map.of(
                "EMERGENCY_MAX_CONNECTIONS", "3", "EMERGENCY_OUTGOING_QUEUE_CAPACITY", "2")));
        for (String value : List.of("0", "65", "", "abc")) {
            assertThrows(IllegalArgumentException.class, () -> ServerLimits.fromEnvironment(Map.of("EMERGENCY_MAX_CONNECTIONS", value)));
        }
        for (String value : List.of("0", "257", "", "abc")) {
            assertThrows(IllegalArgumentException.class, () -> ServerLimits.fromEnvironment(Map.of("EMERGENCY_OUTGOING_QUEUE_CAPACITY", value)));
        }
    }

    @Test
    void fullConnectionCapacityRejectsRatherThanQueueingUnservicedTasks() throws Exception {
        ThreadPoolExecutor executor = ServerMain.connectionExecutor(new ServerLimits(2, 4));
        CountDownLatch occupied = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        Runnable reader = () -> {
            occupied.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        try {
            executor.execute(reader); executor.execute(reader);
            assertTrue(occupied.await(3, TimeUnit.SECONDS));
            assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("Must not wait for a worker")));
            assertEquals(0, executor.getQueue().size());
        } finally {
            release.countDown(); executor.shutdownNow();
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
