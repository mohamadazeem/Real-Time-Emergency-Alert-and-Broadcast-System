package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.common.Messages.Alert;
import edu.ec5205.alert.common.Priority;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.time.Duration;
import java.util.List;

/**
 * Panel displaying server alert history with live in-memory search filter.
 * Modernized with clean card layout, priority badges, and search capability.
 */
public final class AlertHistoryPanel extends JPanel {

    private final AdminAuthService authService;
    private final DefaultTableModel tableModel = new DefaultTableModel(
            new String[]{"Created At (UTC)", "Priority", "Category", "Title", "Alert ID"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private final JTable historyTable = new JTable(tableModel);
    private final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(tableModel);

    private final JLabel statusLabel = new JLabel("Click 'Query Server History' to retrieve past alerts.");
    private final JButton queryButton = new JButton("🔄  Refresh History");
    private final JProgressBar progressBar = new JProgressBar();
    private final JTextField searchField = new JTextField(18);

    public AlertHistoryPanel(AdminAuthService authService) {
        this.authService = authService;
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(15, 15));
        setBackground(Theme.CONTENT_BG);
        setBorder(new EmptyBorder(20, 24, 20, 24));

        // Top info card with title & search bar
        JPanel topCard = Theme.card(new BorderLayout(12, 10));

        JPanel titleBox = new JPanel(new BorderLayout(4, 4));
        titleBox.setOpaque(false);
        JLabel titleLabel = new JLabel("Broadcast History");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 17));
        titleLabel.setForeground(Theme.TEXT_PRIMARY);

        JLabel infoLabel = new JLabel("Historical record of emergency alerts stored in the server SQLite database.");
        infoLabel.setFont(Theme.FONT_SMALL);
        infoLabel.setForeground(Theme.TEXT_SECONDARY);

        titleBox.add(titleLabel, BorderLayout.NORTH);
        titleBox.add(infoLabel, BorderLayout.SOUTH);
        topCard.add(titleBox, BorderLayout.NORTH);

        // Search bar row
        JPanel searchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        searchRow.setOpaque(false);
        JLabel searchIcon = new JLabel("🔍  Search:");
        searchIcon.setFont(Theme.FONT_LABEL);
        searchIcon.setForeground(Theme.TEXT_PRIMARY);
        searchRow.add(searchIcon);

        searchField.setFont(Theme.FONT_BODY);
        searchField.setBorder(new CompoundBorder(
                new LineBorder(Theme.BORDER, 1),
                new EmptyBorder(6, 10, 6, 10)
        ));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
            private void applyFilter() {
                String text = searchField.getText().trim();
                if (text.isEmpty()) {
                    sorter.setRowFilter(null);
                } else {
                    sorter.setRowFilter(RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text)));
                }
            }
        });
        searchRow.add(searchField);
        topCard.add(searchRow, BorderLayout.CENTER);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        topCard.add(progressBar, BorderLayout.SOUTH);
        add(topCard, BorderLayout.NORTH);

        // Center table card
        JPanel centerCard = Theme.card(new BorderLayout(0, 0));
        centerCard.setBorder(new CompoundBorder(
                new LineBorder(Theme.BORDER, 1),
                new EmptyBorder(0, 0, 0, 0)
        ));

        historyTable.setRowSorter(sorter);
        Theme.styleTable(historyTable);

        historyTable.getColumnModel().getColumn(0).setPreferredWidth(170);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(90);
        historyTable.getColumnModel().getColumn(2).setPreferredWidth(90);
        historyTable.getColumnModel().getColumn(3).setPreferredWidth(260);
        historyTable.getColumnModel().getColumn(4).setPreferredWidth(220);

        // Priority column renderer
        historyTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                           boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (value != null && !isSelected) {
                    String str = value.toString();
                    if ("CRITICAL".equalsIgnoreCase(str)) {
                        setForeground(Theme.DANGER);
                        setFont(Theme.FONT_LABEL);
                    } else if ("MEDIUM".equalsIgnoreCase(str)) {
                        setForeground(Theme.WARNING);
                        setFont(Theme.FONT_LABEL);
                    } else {
                        setForeground(Theme.SUCCESS);
                        setFont(Theme.FONT_LABEL);
                    }
                }
                setHorizontalAlignment(SwingConstants.CENTER);
                return c;
            }
        });

        JScrollPane scrollPane = new JScrollPane(historyTable);
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

        queryButton.setFont(Theme.FONT_LABEL);
        queryButton.setBackground(Color.WHITE);
        queryButton.setForeground(Theme.ACCENT);
        queryButton.setBorder(new CompoundBorder(
                new LineBorder(Theme.ACCENT, 1),
                new EmptyBorder(8, 16, 8, 16)
        ));
        queryButton.setFocusPainted(false);
        queryButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        queryButton.addActionListener(e -> queryHistory());
        bottomPanel.add(queryButton, BorderLayout.EAST);

        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void queryHistory() {
        queryButton.setEnabled(false);
        progressBar.setVisible(true);
        statusLabel.setForeground(Theme.ACCENT);
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
                    statusLabel.setForeground(Theme.SUCCESS);
                    statusLabel.setText("Loaded " + alerts.size() + " recorded alert(s) from server.");
                } catch (Exception ex) {
                    tableModel.setRowCount(0);
                    statusLabel.setForeground(Theme.DANGER);
                    statusLabel.setText("Server error: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }
}
