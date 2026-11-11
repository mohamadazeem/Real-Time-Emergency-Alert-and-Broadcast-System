package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.network.MessageListener;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.common.Messages;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises real panel listeners/models; no duplicated UI implementation in tests. */
class PanelLiveUpdatesTest {
    @SuppressWarnings("unchecked")
    static <T> T field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(owner);
    }

    void lifecycle(boolean monitoring) throws Exception {
        try (AdminConnection connection = new AdminConnection()) {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JPanel panel = monitoring ? new AlertMonitoringPanel(connection, new AdminAuthService(connection))
                            : new ConnectedClientsPanel(connection, new AdminAuthService(connection));
                    List<MessageListener> listeners = field(connection, "listeners");
                    assertEquals(1, listeners.size());
                    // JTabbedPane hiding/showing generates these same ancestor callbacks.
                    for (var listener : panel.getAncestorListeners()) listener.ancestorRemoved(null);
                    assertEquals(0, listeners.size());
                    for (var listener : panel.getAncestorListeners()) listener.ancestorAdded(null);
                    for (var listener : panel.getAncestorListeners()) listener.ancestorAdded(null);
                    assertEquals(1, listeners.size(), "reattach exactly once after returning to tab");
                    if (monitoring) ((AlertMonitoringPanel) panel).dispose();
                    else ((ConnectedClientsPanel) panel).dispose();
                    assertTrue(listeners.isEmpty(), "explicit cleanup still removes listener");
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
    }
    @Test void connectedClientUpdatesResumeAfterTabReturns() throws Exception { lifecycle(false); }
    @Test void ackUpdatesResumeAfterTabReturns() throws Exception { lifecycle(true); }

    @Test void queuedAckCannotBeAddedToADifferentSelectedAlert() throws Exception {
        try (AdminConnection connection = new AdminConnection()) {
            AlertMonitoringPanel[] panel = new AlertMonitoringPanel[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    panel[0] = new AlertMonitoringPanel(connection, new AdminAuthService(connection));
                    JComboBox<AlertMonitoringPanel.AlertEntry> combo = field(panel[0], "alertCombo");
                    String first = UUID.randomUUID().toString();
                    String second = UUID.randomUUID().toString();
                    combo.addItem(new AlertMonitoringPanel.AlertEntry(first, "first"));
                    combo.addItem(new AlertMonitoringPanel.AlertEntry(second, "second"));
                    MessageListener listener = field(panel[0], "ackStatusListener");
                    listener.onMessage(new Messages.AckStatus(first, UUID.randomUUID().toString(), "2026-11-11T00:00:00Z"));
                    combo.setSelectedIndex(1); // Selection changes before the queued callback executes.
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            SwingUtilities.invokeAndWait(() -> {
                try {
                    JTable table = field(panel[0], "ackTable");
                    assertEquals(0, table.getRowCount(), "old alert status must not contaminate new selection");
                    panel[0].dispose();
                } catch (Exception e) { throw new RuntimeException(e); }
            });
        }
    }
}
