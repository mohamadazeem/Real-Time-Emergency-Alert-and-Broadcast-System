package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.common.Messages.Alert;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.time.Duration;
import java.util.List;

/**
 * Panel displaying server alert history or current milestone availability.
 */
public final class AlertHistoryPanel extends JPanel {

    private final AdminAuthService authService;
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"Created At (UTC)", "Priority", "Category", "Title", "Alert ID"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable historyTable = new JTable(tableModel);
    private final JLabel statusLabel = new JLabel("Click 'Query Server History' to retrieve past alerts.");
    private final JButton queryButton = new JButton("Query Server History");
    private final JProgressBar progressBar = new JProgressBar();

    public AlertHistoryPanel(AdminAuthService authService) {
        this.authService = authService;
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(10, 10));
        setBorder(new EmptyBorder(15, 20, 15, 20));

        // Header info
        JPanel topPanel = new JPanel(new BorderLayout(10, 10));
        JLabel infoLabel = new JLabel("<html><b>Broadcast History:</b> Recent emergency alerts stored in server database.</html>");
        topPanel.add(infoLabel, BorderLayout.NORTH);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        topPanel.add(progressBar, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        // Center table
        historyTable.setFillsViewportHeight(true);
        historyTable.setRowHeight(24);
        add(new JScrollPane(historyTable), BorderLayout.CENTER);

        // Bottom Controls
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 5));
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        bottomPanel.add(statusLabel, BorderLayout.WEST);

        queryButton.addActionListener(e -> queryHistory());
        bottomPanel.add(queryButton, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void queryHistory() {
        queryButton.setEnabled(false);
        progressBar.setVisible(true);
        statusLabel.setForeground(Color.DARK_GRAY);
        statusLabel.setText("Querying server for alert history...");

        SwingWorker<List<Alert>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<Alert> doInBackground() throws Exception {
                return authService.requestHistory(50, Duration.ofSeconds(4));
            }

            @Override
            protected void done() {
                queryButton.setEnabled(true);
                progressBar.setVisible(false);
                try {
                    List<Alert> alerts = get();
                    tableModel.setRowCount(0);
                    for (Alert alert : alerts) {
                        tableModel.addRow(new Object[]{
                                alert.createdAt(),
                                alert.priority(),
                                alert.category(),
                                alert.title(),
                                alert.id()
                        });
                    }
                    statusLabel.setForeground(new Color(20, 120, 30));
                    statusLabel.setText("Loaded " + alerts.size() + " recorded alert(s).");
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

