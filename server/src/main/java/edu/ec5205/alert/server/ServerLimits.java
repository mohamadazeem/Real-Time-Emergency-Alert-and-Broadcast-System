package edu.ec5205.alert.server;

import java.util.Map;

/** Bounds persistent readers and dedicated writers. There are no waiting reader tasks. */
public record ServerLimits(int maxConnections, int outgoingQueueCapacity) {
    public ServerLimits {
        if (maxConnections < 1 || maxConnections > 64) throw new IllegalArgumentException("EMERGENCY_MAX_CONNECTIONS must be between 1 and 64.");
        if (outgoingQueueCapacity < 1 || outgoingQueueCapacity > 256) throw new IllegalArgumentException("EMERGENCY_OUTGOING_QUEUE_CAPACITY must be between 1 and 256.");
    }
    public static ServerLimits fromEnvironment(Map<String, String> environment) {
        try {
            return new ServerLimits(Integer.parseInt(environment.getOrDefault("EMERGENCY_MAX_CONNECTIONS", "8")),
                    Integer.parseInt(environment.getOrDefault("EMERGENCY_OUTGOING_QUEUE_CAPACITY", "32")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("EMERGENCY_MAX_CONNECTIONS and EMERGENCY_OUTGOING_QUEUE_CAPACITY must be bounded integers.");
        }
    }
}
