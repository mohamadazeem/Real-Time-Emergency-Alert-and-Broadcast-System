package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.network.MessageListener;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.common.Messages.ClientInfo;
import edu.ec5205.alert.common.ProtocolMessage;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Duration;
import java.util.List;

/**
 * Panel displaying connected receiver clients.
 * <p>
 * Supports both a manual "Refresh" button (GET_CLIENTS request-response)
 * and automatic live updates from unsolicited CLIENT_LIST push messages
 * sent by the server when receivers connect or disconnect.
 */
public final class ConnectedClientsPanel extends JPanel {

    private final AdminConnection connection;
    private final AdminAuthService authService;
    private final DefaultTableModel tableModel = new DefaultTableModel(new String[]{"Client ID", "Device Name"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable clientTable = new JTable(tableModel);
    private final JLabel statusLabel = new JLabel("Click 'Refresh List' to query connected clients from server.");
    private final JButton refreshButton = new JButton("Refresh Client List");
    private final JProgressBar progressBar = new JProgressBar();

    /** Listener for unsolicited CLIENT_LIST push messages from the server. */
    private final MessageListener clientListListener = new MessageListener() {
        @Override
        public void onMessage(ProtocolMessage message) {
            if (!(message instanceof edu.ec5205.alert.common.Messages.ClientList list)) return;
            SwingUtilities.invokeLater(() -> {
                tableModel.setRowCount(0);
                for (ClientInfo c : list.clients()) {
                    tableModel.addRow(new Object[]{c.clientId(), c.name()});
                }
                statusLabel.setForeground(new Color(20, 120, 30));
                statusLabel.setText("Live update: " + list.clients().size() + " active receiver(s).");
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
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(15, 20, 15, 20));

        // Header info
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        JLabel infoLabel = new JLabel("<html><b>Connected Receivers:</b> Shows active client endpoints registered on the server. "
                + "Updates automatically when receivers connect or disconnect.</html>");
        topPanel.add(infoLabel, BorderLayout.NORTH);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        topPanel.add(progressBar, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        // Center table
        clientTable.setFillsViewportHeight(true);
        clientTable.setRowHeight(24);
        add(new JScrollPane(clientTable), BorderLayout.CENTER);

        // Bottom Controls
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 5));
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        bottomPanel.add(statusLabel, BorderLayout.WEST);

        refreshButton.addActionListener(e -> refreshClients());
        bottomPanel.add(refreshButton, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void refreshClients() {
        refreshButton.setEnabled(false);
        progressBar.setVisible(true);
        statusLabel.setForeground(Color.DARK_GRAY);
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
                        tableModel.addRow(new Object[]{client.clientId(), client.name()});
                    }
                    statusLabel.setForeground(new Color(20, 120, 30));
                    statusLabel.setText("Successfully loaded " + clients.size() + " active receiver(s).");
                } catch (Exception ex) {
                    tableModel.setRowCount(0);
                    statusLabel.setForeground(new Color(180, 40, 40));
                    statusLabel.setText("Server: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void registerCleanupOnRemoval() {
        addAncestorListener(new AncestorListener() {
            @Override public void ancestorAdded(AncestorEvent e) {}
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


