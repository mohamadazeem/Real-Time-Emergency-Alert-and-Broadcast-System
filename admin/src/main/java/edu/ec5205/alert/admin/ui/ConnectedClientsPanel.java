package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.network.MessageListener;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.common.Messages.ClientInfo;
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
import java.util.List;

/**
 * Panel displaying connected receiver clients.
 * Modernized with clean card layout, styled table with status indicators, and live push updates.
 */
public final class ConnectedClientsPanel extends JPanel {

    private final AdminConnection connection;
    private final AdminAuthService authService;
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"Client ID", "Device Name", "Status"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable clientTable = new JTable(tableModel);
    private final JLabel statusLabel = new JLabel("Click 'Refresh Client List' to query connected clients from server.");
    private final JButton refreshButton = new JButton("🔄  Refresh Client List");
    private final JProgressBar progressBar = new JProgressBar();

    /** Listener for unsolicited CLIENT_LIST push messages from the server. */
    private final MessageListener clientListListener = new MessageListener() {
        @Override
        public void onMessage(ProtocolMessage message) {
            if (!(message instanceof edu.ec5205.alert.common.Messages.ClientList list)) return;
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
                for (ClientInfo c : list.clients()) {
                    tableModel.addRow(new Object[]{c.clientId(), c.name(), "● Online"});
                }
                statusLabel.setForeground(Theme.SUCCESS);
                statusLabel.setText("Live update: " + list.clients().size() + " active receiver(s) registered.");
            });
        }
    };

    public ConnectedClientsPanel(AdminConnection connection, AdminAuthService authService) {
        this.connection  = connection;
        this.authService = authService;
        initUI();
        connection.addListener(clientListListener);
        registerCleanupOnRemoval();
    }

    private void initUI() {
        setLayout(new BorderLayout(15, 15));
        setBackground(Theme.CONTENT_BG);
        setBorder(new EmptyBorder(20, 24, 20, 24));

        // Top info card
        JPanel topCard = Theme.card(new BorderLayout(10, 8));
        JLabel titleLabel = new JLabel("Connected Receivers");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 17));
        titleLabel.setForeground(Theme.TEXT_PRIMARY);

        JLabel infoLabel = new JLabel("<html>Shows active client endpoints currently registered with the broadcast server. "
                + "Updates automatically in real time as clients connect or disconnect.</html>");
        infoLabel.setFont(Theme.FONT_SMALL);
        infoLabel.setForeground(Theme.TEXT_SECONDARY);

        topCard.add(titleLabel, BorderLayout.NORTH);
        topCard.add(infoLabel, BorderLayout.CENTER);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        topCard.add(progressBar, BorderLayout.SOUTH);
        add(topCard, BorderLayout.NORTH);

        // Center card with table
        JPanel centerCard = Theme.card(new BorderLayout(0, 0));
        centerCard.setBorder(new CompoundBorder(
                new LineBorder(Theme.BORDER, 1),
                new EmptyBorder(0, 0, 0, 0)
        ));

        Theme.styleTable(clientTable);
        clientTable.getColumnModel().getColumn(0).setPreferredWidth(260);
        clientTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        clientTable.getColumnModel().getColumn(2).setPreferredWidth(100);

        // Custom renderer for Status column (column index 2)
        clientTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    setForeground(Theme.SUCCESS);
                    setFont(Theme.FONT_LABEL);
                }
                setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(clientTable);
        scrollPane.setBorder(null);
        scrollPane.getViewport().setBackground(Color.WHITE);
        centerCard.add(scrollPane, BorderLayout.CENTER);
        add(centerCard, BorderLayout.CENTER);

        // Bottom Controls
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 5));
        bottomPanel.setOpaque(false);
        statusLabel.setFont(Theme.FONT_BODY);
        statusLabel.setForeground(Theme.TEXT_SECONDARY);
        bottomPanel.add(statusLabel, BorderLayout.WEST);

        refreshButton.setFont(Theme.FONT_LABEL);
        refreshButton.setBackground(Color.WHITE);
        refreshButton.setForeground(Theme.ACCENT);
        refreshButton.setBorder(new CompoundBorder(
                new LineBorder(Theme.ACCENT, 1),
                new EmptyBorder(8, 16, 8, 16)
        ));
        refreshButton.setFocusPainted(false);
        refreshButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        refreshButton.addActionListener(e -> refreshClients());
        bottomPanel.add(refreshButton, BorderLayout.EAST);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void refreshClients() {
        refreshButton.setEnabled(false);
        progressBar.setVisible(true);
        statusLabel.setForeground(Theme.ACCENT);
        statusLabel.setText("Querying server for registered clients...");

        SwingWorker<List<ClientInfo>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<ClientInfo> doInBackground() throws Exception {
                return authService.requestConnectedClients(Duration.ofSeconds(4));
            }

            @Override
            protected void done() {
                refreshButton.setEnabled(true);
                progressBar.setVisible(false);
                try {
                    List<ClientInfo> clients = get();
                    tableModel.setRowCount(0);
                    for (ClientInfo client : clients) {
                        tableModel.addRow(new Object[]{client.clientId(), client.name(), "● Online"});
                    }
                    statusLabel.setForeground(Theme.SUCCESS);
                    statusLabel.setText("Successfully loaded " + clients.size() + " active receiver(s).");
                } catch (Exception ex) {
                    tableModel.setRowCount(0);
                    statusLabel.setForeground(Theme.DANGER);
                    statusLabel.setText("Server error: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void registerCleanupOnRemoval() {
        addAncestorListener(new AncestorListener() {
            @Override public void ancestorAdded(AncestorEvent e) {
                connection.removeListener(clientListListener);
                connection.addListener(clientListListener);
            }
            @Override public void ancestorMoved(AncestorEvent e) {}

            @Override
            public void ancestorRemoved(AncestorEvent e) {
                connection.removeListener(clientListListener);
            }
        });
    }

    /** Public dispose hook for explicit cleanup. */
    public void dispose() {
        connection.removeListener(clientListListener);
    }
}
