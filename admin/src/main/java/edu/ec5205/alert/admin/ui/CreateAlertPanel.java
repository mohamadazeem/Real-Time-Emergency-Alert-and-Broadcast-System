package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.service.AlertService;
import edu.ec5205.alert.admin.service.AlertService.AlertCreationResult;
import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.Priority;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Objects;

/**
 * Swing panel providing the emergency alert creation form and broadcast trigger.
 * Modernized with clean card layout, vertical field stacking, and live visual feedback.
 */
public final class CreateAlertPanel extends JPanel {

    private final AlertService alertService;

    private final JTextField titleField = new JTextField();
    private final JTextArea messageArea = new JTextArea(6, 40);
    private final JComboBox<Category> categoryCombo = new JComboBox<>(Category.values());
    private final JComboBox<Priority> priorityCombo = new JComboBox<>(Priority.values());

    private final JLabel titleCounterLabel = new JLabel("0 / " + AlertService.MAX_TITLE_LENGTH);
    private final JLabel messageCounterLabel = new JLabel("0 / " + AlertService.MAX_MESSAGE_LENGTH);
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton broadcastButton = new JButton("🚨  BROADCAST EMERGENCY ALERT");
    private final JButton clearButton = new JButton("Clear Form");

    public CreateAlertPanel(AlertService alertService) {
        this.alertService = Objects.requireNonNull(alertService, "alertService cannot be null");
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(15, 15));
        setBackground(Theme.CONTENT_BG);
        setBorder(new EmptyBorder(20, 24, 20, 24));

        // Form Card Panel
        JPanel cardPanel = Theme.card(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 8, 6, 8);

        // Header section inside card
        JPanel cardHeader = new JPanel(new BorderLayout(5, 5));
        cardHeader.setOpaque(false);
        JLabel titleLabel = new JLabel("Create New Emergency Broadcast");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 17));
        titleLabel.setForeground(Theme.TEXT_PRIMARY);

        JLabel subtitleLabel = new JLabel("Broadcast a high-priority warning to all active receiver terminals across the network.");
        subtitleLabel.setFont(Theme.FONT_SMALL);
        subtitleLabel.setForeground(Theme.TEXT_SECONDARY);

        cardHeader.add(titleLabel, BorderLayout.NORTH);
        cardHeader.add(subtitleLabel, BorderLayout.SOUTH);
        cardHeader.setBorder(new EmptyBorder(0, 0, 10, 0));

        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2; gbc.weightx = 1.0;
        cardPanel.add(cardHeader, gbc);

        // Row 1: Alert Title Label & Counter
        JPanel titleHeaderPanel = new JPanel(new BorderLayout());
        titleHeaderPanel.setOpaque(false);
        JLabel fTitleLabel = new JLabel("Alert Title:");
        fTitleLabel.setFont(Theme.FONT_LABEL);
        fTitleLabel.setForeground(Theme.TEXT_PRIMARY);
        titleCounterLabel.setFont(Theme.FONT_MONO);
        titleCounterLabel.setForeground(Theme.TEXT_SECONDARY);
        titleHeaderPanel.add(fTitleLabel, BorderLayout.WEST);
        titleHeaderPanel.add(titleCounterLabel, BorderLayout.EAST);

        gbc.gridy = 1; gbc.insets = new Insets(8, 8, 2, 8);
        cardPanel.add(titleHeaderPanel, gbc);

        // Row 2: Alert Title Input
        titleField.setFont(Theme.FONT_BODY);
        titleField.setBorder(new CompoundBorder(
                new LineBorder(Theme.BORDER, 1),
                new EmptyBorder(8, 10, 8, 10)
        ));
        gbc.gridy = 2; gbc.insets = new Insets(2, 8, 10, 8);
        cardPanel.add(titleField, gbc);

        // Row 3: Classification row (Category + Priority)
        JPanel metaRow = new JPanel(new GridLayout(1, 2, 16, 0));
        metaRow.setOpaque(false);

        // Category Box
        JPanel catBox = new JPanel(new BorderLayout(4, 4));
        catBox.setOpaque(false);
        JLabel catLabel = new JLabel("📂 Category:");
        catLabel.setFont(Theme.FONT_LABEL);
        catLabel.setForeground(Theme.TEXT_PRIMARY);
        categoryCombo.setFont(Theme.FONT_BODY);
        categoryCombo.setBackground(Color.WHITE);
        catBox.add(catLabel, BorderLayout.NORTH);
        catBox.add(categoryCombo, BorderLayout.CENTER);
        metaRow.add(catBox);

        // Priority Box
        JPanel prioBox = new JPanel(new BorderLayout(4, 4));
        prioBox.setOpaque(false);
        JLabel prioLabel = new JLabel("⚡ Priority Level:");
        prioLabel.setFont(Theme.FONT_LABEL);
        prioLabel.setForeground(Theme.TEXT_PRIMARY);
        priorityCombo.setFont(Theme.FONT_BODY);
        priorityCombo.setBackground(Color.WHITE);
        priorityCombo.setSelectedItem(Priority.CRITICAL);
        prioBox.add(prioLabel, BorderLayout.NORTH);
        prioBox.add(priorityCombo, BorderLayout.CENTER);
        metaRow.add(prioBox);

        gbc.gridy = 3; gbc.insets = new Insets(4, 8, 12, 8);
        cardPanel.add(metaRow, gbc);

        // Row 4: Message Body Label & Counter
        JPanel msgHeaderPanel = new JPanel(new BorderLayout());
        msgHeaderPanel.setOpaque(false);
        JLabel msgLabel = new JLabel("Emergency Message Body:");
        msgLabel.setFont(Theme.FONT_LABEL);
        msgLabel.setForeground(Theme.TEXT_PRIMARY);
        messageCounterLabel.setFont(Theme.FONT_MONO);
        messageCounterLabel.setForeground(Theme.TEXT_SECONDARY);
        msgHeaderPanel.add(msgLabel, BorderLayout.WEST);
        msgHeaderPanel.add(messageCounterLabel, BorderLayout.EAST);

        gbc.gridy = 4; gbc.insets = new Insets(6, 8, 2, 8);
        cardPanel.add(msgHeaderPanel, gbc);

        // Row 5: Message TextArea
        messageArea.setFont(Theme.FONT_BODY);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setBorder(new EmptyBorder(8, 10, 8, 10));

        JScrollPane scrollPane = new JScrollPane(messageArea);
        scrollPane.setBorder(new LineBorder(Theme.BORDER, 1));
        gbc.gridy = 5; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(2, 8, 16, 8);
        cardPanel.add(scrollPane, gbc);

        // Wrap card inside a scrollable or centered container
        JScrollPane outerScroll = new JScrollPane(cardPanel);
        outerScroll.setBorder(null);
        outerScroll.getViewport().setBackground(Theme.CONTENT_BG);
        add(outerScroll, BorderLayout.CENTER);

        // Bottom Action and Status Panel
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));
        bottomPanel.setOpaque(false);

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);

        statusLabel.setFont(Theme.FONT_BODY);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel statusBox = new JPanel(new BorderLayout(6, 6));
        statusBox.setOpaque(false);
        statusBox.add(progressBar, BorderLayout.NORTH);
        statusBox.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.add(statusBox, BorderLayout.NORTH);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        buttonBar.setOpaque(false);

        clearButton.setFont(Theme.FONT_LABEL);
        clearButton.setBackground(Color.WHITE);
        clearButton.setForeground(Theme.TEXT_PRIMARY);
        clearButton.setBorder(new CompoundBorder(
                new LineBorder(Theme.BORDER, 1),
                new EmptyBorder(9, 18, 9, 18)
        ));
        clearButton.setFocusPainted(false);
        clearButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearButton.addActionListener(e -> clearForm());

        broadcastButton.setFont(new Font("SansSerif", Font.BOLD, 13));
        broadcastButton.setBackground(Theme.DANGER);
        broadcastButton.setForeground(Color.WHITE);
        broadcastButton.setBorder(new EmptyBorder(10, 24, 10, 24));
        broadcastButton.setFocusPainted(false);
        broadcastButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        broadcastButton.addActionListener(e -> onBroadcastClicked());

        buttonBar.add(clearButton);
        buttonBar.add(broadcastButton);
        bottomPanel.add(buttonBar, BorderLayout.SOUTH);

        add(bottomPanel, BorderLayout.SOUTH);

        // Setup Document Listeners for Live Character Counters
        setupDocumentListeners();
    }

    private void setupDocumentListeners() {
        titleField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateCount(); }
            @Override public void removeUpdate(DocumentEvent e) { updateCount(); }
            @Override public void changedUpdate(DocumentEvent e) { updateCount(); }
            private void updateCount() {
                int count = titleField.getText().codePointCount(0, titleField.getText().length());
                titleCounterLabel.setText(count + " / " + AlertService.MAX_TITLE_LENGTH);
                if (count > AlertService.MAX_TITLE_LENGTH) {
                    titleCounterLabel.setForeground(Theme.DANGER);
                } else {
                    titleCounterLabel.setForeground(Theme.TEXT_SECONDARY);
                }
            }
        });

        messageArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { updateCount(); }
            @Override public void removeUpdate(DocumentEvent e) { updateCount(); }
            @Override public void changedUpdate(DocumentEvent e) { updateCount(); }
            private void updateCount() {
                int count = messageArea.getText().codePointCount(0, messageArea.getText().length());
                messageCounterLabel.setText(count + " / " + AlertService.MAX_MESSAGE_LENGTH);
                if (count > AlertService.MAX_MESSAGE_LENGTH) {
                    messageCounterLabel.setForeground(Theme.DANGER);
                } else {
                    messageCounterLabel.setForeground(Theme.TEXT_SECONDARY);
                }
            }
        });
    }

    private void onBroadcastClicked() {
        String title = titleField.getText();
        String message = messageArea.getText();
        Category category = (Category) categoryCombo.getSelectedItem();
        Priority priority = (Priority) priorityCombo.getSelectedItem();

        // 1. Client-Side Input Validation
        try {
            alertService.validateAlertInput(title, message, category, priority);
        } catch (IllegalArgumentException e) {
            showError("Input Error: " + e.getMessage());
            return;
        }

        // 2. Prevent duplicate clicks during transmission
        setFormEnabled(false);
        progressBar.setVisible(true);
        statusLabel.setForeground(Theme.ACCENT);
        statusLabel.setText("Broadcasting alert to server over TCP...");

        // 3. Execute network call via SwingWorker (never blocks EDT)
        SwingWorker<AlertCreationResult, Void> worker = new SwingWorker<>() {
            @Override
            protected AlertCreationResult doInBackground() {
                return alertService.broadcastAlert(title, message, category, priority);
            }

            @Override
            protected void done() {
                setFormEnabled(true);
                progressBar.setVisible(false);
                try {
                    AlertCreationResult result = get();
                    if (result.successful()) {
                        showSuccess("<html><b>ALERT ACCEPTED BY SERVER:</b> Broadcast successfully queued.<br/>" +
                                "Server Alert ID: <code>" + result.alertId() + "</code> | Request ID: <code>" + result.requestId() + "</code></html>");
                    } else {
                        showError(result.message());
                    }
                } catch (Exception ex) {
                    showError("Failed to broadcast alert: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void setFormEnabled(boolean enabled) {
        broadcastButton.setEnabled(enabled);
        clearButton.setEnabled(enabled);
        titleField.setEnabled(enabled);
        messageArea.setEnabled(enabled);
        categoryCombo.setEnabled(enabled);
        priorityCombo.setEnabled(enabled);
    }

    private void showSuccess(String htmlMessage) {
        statusLabel.setForeground(Theme.SUCCESS);
        statusLabel.setText(htmlMessage);
    }

    private void showError(String errorMessage) {
        statusLabel.setForeground(Theme.DANGER);
        statusLabel.setText(errorMessage);
    }

    private void clearForm() {
        titleField.setText("");
        messageArea.setText("");
        categoryCombo.setSelectedIndex(0);
        priorityCombo.setSelectedItem(Priority.CRITICAL);
        statusLabel.setText(" ");
        titleField.requestFocusInWindow();
    }
}
