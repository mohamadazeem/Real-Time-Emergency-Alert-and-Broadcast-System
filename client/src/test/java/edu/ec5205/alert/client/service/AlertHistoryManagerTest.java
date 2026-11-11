package edu.ec5205.alert.client.service;

import edu.ec5205.alert.client.model.AckStatusState;
import edu.ec5205.alert.client.model.ReceivedAlert;
import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Priority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class AlertHistoryManagerTest {

    private AlertHistoryManager historyManager;

    @BeforeEach
    void setUp() {
        historyManager = new AlertHistoryManager();
    }

    @Test
    void addsNewAlertSuccessfully() {
        Alert alert = new Alert(UUID.randomUUID().toString(), "2026-11-11T10:00:00Z",
                "Fire Warning", "Evacuate south wing", Category.FIRE, Priority.CRITICAL);

        boolean added = historyManager.addAlert(alert);
        assertTrue(added);
        assertEquals(1, historyManager.getAllAlerts().size());
        assertTrue(historyManager.contains(alert.id()));

        ReceivedAlert received = historyManager.getAlert(alert.id()).orElseThrow();
        assertEquals("Fire Warning", received.title());
        assertEquals(Category.FIRE, received.category());
        assertEquals(Priority.CRITICAL, received.priority());
        assertEquals(AckStatusState.UNACKNOWLEDGED, received.ackStatus());
    }

    @Test
    void preventsDuplicateAlertsById() {
        String alertId = UUID.randomUUID().toString();
        Alert alert1 = new Alert(alertId, "2026-11-11T10:00:00Z",
                "Flood Warning", "Heavy rain", Category.WEATHER, Priority.MEDIUM);
        Alert alert2 = new Alert(alertId, "2026-11-11T10:05:00Z",
                "Flood Warning", "Heavy rain update", Category.WEATHER, Priority.CRITICAL);

        assertTrue(historyManager.addAlert(alert1), "First alert should be added");
        assertFalse(historyManager.addAlert(alert2), "Duplicate alert with same ID must be rejected");
        assertEquals(1, historyManager.getAllAlerts().size());
    }

    @Test
    void updatesAckStatusCorrectly() {
        Alert alert = new Alert(UUID.randomUUID().toString(), "2026-11-11T10:00:00Z",
                "Security Alert", "Lockdown", Category.SECURITY, Priority.CRITICAL);
        historyManager.addAlert(alert);

        assertTrue(historyManager.updateAckStatus(alert.id(), AckStatusState.PENDING));
        assertEquals(AckStatusState.PENDING, historyManager.getAlert(alert.id()).orElseThrow().ackStatus());

        assertTrue(historyManager.updateAckStatus(alert.id(), AckStatusState.ACKNOWLEDGED));
        assertEquals(AckStatusState.ACKNOWLEDGED, historyManager.getAlert(alert.id()).orElseThrow().ackStatus());

        assertFalse(historyManager.updateAckStatus(UUID.randomUUID().toString(), AckStatusState.ACKNOWLEDGED),
                "Updating non-existent alert should return false");
    }

    @Test
    void mergesHistoryPreservingExistingAckStatus() {
        String alert1Id = UUID.randomUUID().toString();
        Alert alert1 = new Alert(alert1Id, "2026-11-11T10:00:00Z", "Alert 1", "Msg 1", Category.GENERAL, Priority.LOW);
        historyManager.addAlert(alert1);
        historyManager.updateAckStatus(alert1Id, AckStatusState.ACKNOWLEDGED);

        String alert2Id = UUID.randomUUID().toString();
        Alert alert2 = new Alert(alert2Id, "2026-11-11T10:10:00Z", "Alert 2", "Msg 2", Category.WEATHER, Priority.MEDIUM);

        List<ReceivedAlert> newlyAdded = historyManager.mergeHistory(List.of(alert1, alert2));
        assertEquals(1, newlyAdded.size(), "Only alert2 should be considered newly added");
        assertEquals(alert2Id, newlyAdded.get(0).id());

        assertEquals(2, historyManager.getAllAlerts().size());
        assertEquals(AckStatusState.ACKNOWLEDGED, historyManager.getAlert(alert1Id).orElseThrow().ackStatus(),
                "Existing ACK status must be preserved");
        assertEquals(AckStatusState.UNACKNOWLEDGED, historyManager.getAlert(alert2Id).orElseThrow().ackStatus());
    }
}
