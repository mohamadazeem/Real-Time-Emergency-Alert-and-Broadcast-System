package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.common.Messages.ClientInfo;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Duration;
import java.util.List;

/**
 * Panel displaying connected receiver clients or current server milestone availability.
 */
public final class ConnectedClientsPanel extends JPanel {

    private final AdminAuthService authService;
    private final DefaultTableModel tableModel = new DefaultTableModel(new String[]{"Client ID", "Device Name"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable clientTable = new JTable(tableModel);
    private final JLabel statusLabel = new JLabel("Click 'Refresh List' to query connected clients from server.");
    private final JButton refreshButton = new JButton("Refresh Client List");
    private final JProgressBar progressBar = new JProgressBar();

    public ConnectedClientsPanel(AdminAuthService authService) {
        this.authService = authService;
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(15, 20, 15, 20));

        // Header info
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        JLabel infoLabel = new JLabel("<html><b>Connected Receivers:</b> Shows active client endpoints registered on the server.</html>");
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
}

