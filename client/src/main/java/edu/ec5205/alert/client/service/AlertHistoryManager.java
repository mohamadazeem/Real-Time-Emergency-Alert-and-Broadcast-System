package edu.ec5205.alert.client.service;

import edu.ec5205.alert.client.model.AckStatusState;
import edu.ec5205.alert.client.model.ReceivedAlert;
import edu.ec5205.alert.common.Messages.Alert;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Thread-safe manager for storing, deduplicating, and tracking received alerts and acknowledgements.
 */
public final class AlertHistoryManager {
    private final Map<String, ReceivedAlert> alertMap = new LinkedHashMap<>();
    private final Object lock = new Object();

    /**
     * Adds an alert if not already present.
     *
     * @param alert Incoming alert
     * @return true if newly added, false if it was already known (duplicate prevented)
     */
    public boolean addAlert(Alert alert) {
        Objects.requireNonNull(alert, "alert cannot be null");
        synchronized (lock) {
            if (alertMap.containsKey(alert.id())) {
                return false;
            }
            alertMap.put(alert.id(), new ReceivedAlert(alert));
            return true;
        }
    }

    /**
     * Merges a list of alerts (e.g., from GET_HISTORY), preserving existing acknowledgement status.
     *
     * @param alerts List of historical alerts
     * @return List of newly added alerts
     */
    public List<ReceivedAlert> mergeHistory(List<Alert> alerts) {
        if (alerts == null || alerts.isEmpty()) {
            return Collections.emptyList();
        }

        List<ReceivedAlert> newlyAdded = new ArrayList<>();
        synchronized (lock) {
            for (Alert alert : alerts) {
                if (alert == null || alert.id() == null) continue;
                if (!alertMap.containsKey(alert.id())) {
                    ReceivedAlert receivedAlert = new ReceivedAlert(alert);
                    alertMap.put(alert.id(), receivedAlert);
                    newlyAdded.add(receivedAlert);
                }
            }
        }
        return newlyAdded;
    }

    /**
     * Updates the acknowledgement status for an alert.
     */
    public boolean updateAckStatus(String alertId, AckStatusState newStatus) {
        Objects.requireNonNull(alertId, "alertId cannot be null");
        Objects.requireNonNull(newStatus, "newStatus cannot be null");
        synchronized (lock) {
            ReceivedAlert received = alertMap.get(alertId);
            if (received != null) {
                received.setAckStatus(newStatus);
                return true;
            }
            return false;
        }
    }

    /**
     * Retrieves an alert by its ID.
     */
    public Optional<ReceivedAlert> getAlert(String alertId) {
        synchronized (lock) {
            return Optional.ofNullable(alertMap.get(alertId));
        }
    }

    /**
     * Returns an unmodifiable snapshot of all received alerts in chronological order.
     */
    public List<ReceivedAlert> getAllAlerts() {
        synchronized (lock) {
            return new ArrayList<>(alertMap.values());
        }
    }

    /**
     * Checks if an alert is already known.
     */
    public boolean contains(String alertId) {
        synchronized (lock) {
            return alertMap.containsKey(alertId);
        }
    }

    /**
     * Clears all alerts (for testing or reset).
     */
    public void clear() {
        synchronized (lock) {
            alertMap.clear();
        }
    }
}

