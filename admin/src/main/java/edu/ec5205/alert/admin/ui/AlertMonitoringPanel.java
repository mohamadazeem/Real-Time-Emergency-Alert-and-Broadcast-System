package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.network.MessageListener;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.common.Messages;
import edu.ec5205.alert.common.ProtocolMessage;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Panel for real-time alert acknowledgement monitoring.
 * Modernized with clean card layout, styled table with acknowledgement markers, and live push updates.
 */
public final class AlertMonitoringPanel extends JPanel {

    private final AdminConnection connection;
    private final AdminAuthService authService;

    // ── ACK table ──────────────────────────────────────────────────────────
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"Client ID", "Acknowledged At"}, 0) {
        @Override
        public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable ackTable = new JTable(tableModel);

    // ── Alert selector ─────────────────────────────────────────────────────
    private final DefaultComboBoxModel<AlertEntry> comboModel = new DefaultComboBoxModel<>();
    private final JComboBox<AlertEntry> alertCombo = new JComboBox<>(comboModel);
    private final JButton loadAlertsButton   = new JButton("📥  Load Alerts");
    private final JButton queryAcksButton    = new JButton("🔍  Query ACKs");
    private final JProgressBar progressBar   = new JProgressBar();
    private final JLabel statusLabel         = new JLabel("Load alerts, then select one to query acknowledgements.");

    // ── Duplicate tracking ─────────────────────────────────────────────────
    /** clientId values already shown for the currently selected alert. */
    private final Set<String> shownClientIds = new HashSet<>();

    // ── Live-update listener ────────────────────────────────────────────────
    private final MessageListener ackStatusListener = new MessageListener() {
        @Override
        public void onMessage(ProtocolMessage message) {
            if (!(message instanceof Messages.AckStatus ackStatus)) return;

            SwingUtilities.invokeLater(() -> {
                // Read selection on the EDT, when this update is actually applied.
                AlertEntry selected = (AlertEntry) alertCombo.getSelectedItem();
                if (selected != null && selected.alertId().equals(ackStatus.alertId())) {
                    addRowIfNotDuplicate(ackStatus.clientId(), ackStatus.acknowledgedAt());
                }
            });
        }
    };

    public AlertMonitoringPanel(AdminConnection connection, AdminAuthService authService) {
        this.connection  = connection;
        this.authService = authService;

        initUI();
        connection.addListener(ackStatusListener);
        registerCleanupOnRemoval();
    }

    // ── UI construction ───────────────────────────────────────────────────

    private void initUI() {
        setLayout(new BorderLayout(15, 15));
        setBackground(Theme.CONTENT_BG);
        setBorder(new EmptyBorder(20, 24, 20, 24));

        add(buildTopPanel(),    BorderLayout.NORTH);
        add(buildCenterPanel(), BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildTopPanel() {
        JPanel top = Theme.card(new BorderLayout(10, 8));

        JPanel titleBox = new JPanel(new BorderLayout(4, 4));
        titleBox.setOpaque(false);
        JLabel titleLabel = new JLabel("Alert Monitoring");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 17));
        titleLabel.setForeground(Theme.TEXT_PRIMARY);

        JLabel info = new JLabel("Query acknowledgements for an alert broadcast, and receive live ACK_STATUS push updates.");
        info.setFont(Theme.FONT_SMALL);
        info.setForeground(Theme.TEXT_SECONDARY);

        titleBox.add(titleLabel, BorderLayout.NORTH);
        titleBox.add(info, BorderLayout.SOUTH);
        top.add(titleBox, BorderLayout.NORTH);

        // Alert selector row
        JPanel selectorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        selectorRow.setOpaque(false);

        JLabel selLabel = new JLabel("Select Alert:");
        selLabel.setFont(Theme.FONT_LABEL);
        selLabel.setForeground(Theme.TEXT_PRIMARY);
        selectorRow.add(selLabel);

        alertCombo.setPreferredSize(new Dimension(380, 32));
        alertCombo.setFont(Theme.FONT_BODY);
        alertCombo.setBackground(Color.WHITE);
        alertCombo.setRenderer(new AlertComboRenderer());
        selectorRow.add(alertCombo);

        loadAlertsButton.setFont(Theme.FONT_LABEL);
        loadAlertsButton.setBackground(Color.WHITE);
        loadAlertsButton.setForeground(Theme.ACCENT);
        loadAlertsButton.setBorder(new CompoundBorder(
                new LineBorder(Theme.ACCENT, 1),
                new EmptyBorder(7, 14, 7, 14)
        ));
        loadAlertsButton.setFocusPainted(false);
        loadAlertsButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        queryAcksButton.setFont(Theme.FONT_LABEL);
        queryAcksButton.setBackground(Theme.ACCENT);
        queryAcksButton.setForeground(Color.WHITE);
        queryAcksButton.setBorder(new EmptyBorder(8, 16, 8, 16));
        queryAcksButton.setFocusPainted(false);
        queryAcksButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        selectorRow.add(loadAlertsButton);
        selectorRow.add(queryAcksButton);
        top.add(selectorRow, BorderLayout.CENTER);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        top.add(progressBar, BorderLayout.SOUTH);

        // Button actions
        loadAlertsButton.addActionListener(e -> loadAlertHistory());
        queryAcksButton.addActionListener(e -> queryAcknowledgements());

        return top;
    }

    private JPanel buildCenterPanel() {
        JPanel centerCard = Theme.card(new BorderLayout(0, 0));
        centerCard.setBorder(new CompoundBorder(
                new LineBorder(Theme.BORDER, 1),
                new EmptyBorder(0, 0, 0, 0)
        ));

        Theme.styleTable(ackTable);
        ackTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        ackTable.getColumnModel().getColumn(1).setPreferredWidth(260);

        // Custom renderer for Acknowledged At with checkmark icon
        ackTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value != null && !isSelected) {
                    setText("✅  " + value);
                    setForeground(Theme.SUCCESS);
                    setFont(Theme.FONT_BODY);
                }
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(ackTable);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Color.WHITE);
        centerCard.add(scrollPane, BorderLayout.CENTER);
        return centerCard;
    }

    private JPanel buildBottomPanel() {
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        statusLabel.setFont(Theme.FONT_BODY);
        statusLabel.setForeground(Theme.TEXT_SECONDARY);
        bottom.add(statusLabel, BorderLayout.WEST);
        return bottom;
    }

    // ── Network actions (off EDT via SwingWorker) ─────────────────────────

    /** Fetches recent alert history to populate the combo box. */
    private void loadAlertHistory() {
        setLoadingState(true, "Loading alert history from server...");

        new SwingWorker<List<Messages.Alert>, Void>() {
            @Override
            protected List<Messages.Alert> doInBackground() throws Exception {
                return authService.requestHistory(50, Duration.ofSeconds(5));
            }

            @Override
            protected void done() {
                setLoadingState(false, null);
                try {
                    List<Messages.Alert> alerts = get();
                    comboModel.removeAllElements();
                    for (Messages.Alert a : alerts) {
                        comboModel.addElement(new AlertEntry(a.id(), a.title()));
                    }
                    if (alerts.isEmpty()) {
                        setStatus(false, "No alerts found in server history.");
                    } else {
                        setStatus(true, "Loaded " + alerts.size() + " alert(s). Select one and click 'Query ACKs'.");
                    }
                } catch (Exception ex) {
                    setStatus(false, "Failed to load history: " + ex.getMessage());
                }
            }
        }.execute();
    }

    /** Sends GET_ACKS for the selected alert and populates the table. */
    private void queryAcknowledgements() {
        AlertEntry selected = (AlertEntry) alertCombo.getSelectedItem();
        if (selected == null) {
            setStatus(false, "Please load alerts first and select one.");
            return;
        }

        setLoadingState(true, "Querying acknowledgements for: " + selected.title() + "...");
        tableModel.setRowCount(0);
        shownClientIds.clear();

        final String alertId = selected.alertId();

        new SwingWorker<List<Messages.Acknowledgement>, Void>() {
            @Override
            protected List<Messages.Acknowledgement> doInBackground() throws Exception {
                return authService.requestAcknowledgements(alertId, Duration.ofSeconds(5));
            }

            @Override
            protected void done() {
                setLoadingState(false, null);
                try {
                    List<Messages.Acknowledgement> acks = get();
                    if (acks.isEmpty()) {
                        setStatus(false, "No clients have acknowledged this alert yet.");
                    } else {
                        for (Messages.Acknowledgement ack : acks) {
                            addRowIfNotDuplicate(ack.clientId(), ack.acknowledgedAt());
                        }
                        setStatus(true, "Loaded " + acks.size() + " acknowledgement(s). Listening for live updates…");
                    }
                } catch (Exception ex) {
                    setStatus(false, "Query failed: " + ex.getMessage());
                }
            }
        }.execute();
    }

    // ── Table helpers ─────────────────────────────────────────────────────

    /**
     * Adds a row to the ACK table only if the clientId has not been seen before.
     * Must be called on the Event Dispatch Thread.
     */
    void addRowIfNotDuplicate(String clientId, String acknowledgedAt) {
        if (shownClientIds.add(clientId)) {
            tableModel.addRow(new Object[]{clientId, acknowledgedAt});
            setStatus(true, shownClientIds.size() + " acknowledgement(s) received.");
        }
    }

    // ── State helpers ──────────────────────────────────────────────────────

    private void setLoadingState(boolean loading, String statusText) {
        progressBar.setVisible(loading);
        loadAlertsButton.setEnabled(!loading);
        queryAcksButton.setEnabled(!loading);
        if (statusText != null) {
            statusLabel.setForeground(Theme.ACCENT);
            statusLabel.setText(statusText);
        }
    }

    private void setStatus(boolean ok, String text) {
        statusLabel.setForeground(ok ? Theme.SUCCESS : Theme.DANGER);
        statusLabel.setText(text);
    }

    // ── Listener cleanup ───────────────────────────────────────────────────

    /**
     * Removes the ACK_STATUS listener when this panel is detached from the UI
     * (e.g., when DashboardFrame is closed or the tab component is replaced).
     */
    private void registerCleanupOnRemoval() {
        addAncestorListener(new AncestorListener() {
            @Override public void ancestorAdded(AncestorEvent e) {
                connection.removeListener(ackStatusListener);
                connection.addListener(ackStatusListener);
            }
            @Override public void ancestorMoved(AncestorEvent e) {}

            @Override
            public void ancestorRemoved(AncestorEvent e) {
                connection.removeListener(ackStatusListener);
            }
        });
    }

    /** Public dispose hook for explicit cleanup (e.g., called from DashboardFrame on close). */
    public void dispose() {
        connection.removeListener(ackStatusListener);
    }

    // ── Helper types ───────────────────────────────────────────────────────

    /** Represents one alert entry in the combo box. */
    record AlertEntry(String alertId, String title) {
        @Override
        public String toString() {
            return title + "  [" + alertId + "]";
        }
    }

    /** Custom renderer to show title + truncated ID cleanly. */
    private static final class AlertComboRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(
                JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {

            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof AlertEntry entry) {
                String id = entry.alertId();
                String shortId = id.length() > 8 ? id.substring(0, 8) + "…" : id;
                setText(entry.title() + "  [" + shortId + "]");
                setToolTipText(entry.toString());
            }
            return this;
        }
    }
}
