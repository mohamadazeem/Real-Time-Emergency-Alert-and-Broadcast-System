package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.service.AlertService;
import edu.ec5205.alert.admin.service.AlertService.AlertCreationResult;
import edu.ec5205.alert.common.Category;
import edu.ec5205.alert.common.Priority;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.Objects;

/**
 * Swing panel providing the emergency alert creation form and broadcast trigger.
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
    private final JButton broadcastButton = new JButton("BROADCAST ALERT");
    private final JButton clearButton = new JButton("Clear Form");

    public CreateAlertPanel(AlertService alertService) {
        this.alertService = Objects.requireNonNull(alertService, "alertService cannot be null");
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(15, 15));
        setBorder(new EmptyBorder(20, 25, 20, 25));

        // Form Container
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(new CompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(new Color(200, 200, 200)),
                        " Create New Emergency Broadcast ",
                        TitledBorder.LEFT,
                        TitledBorder.TOP,
                        new Font("SansSerif", Font.BOLD, 14),
                        new Color(40, 40, 40)
                ),
                new EmptyBorder(15, 15, 15, 15)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);

        // Row 1: Title
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        JLabel titleLabel = new JLabel("Alert Title:");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        formPanel.add(titleLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        formPanel.add(titleField, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.0;
        titleCounterLabel.setForeground(Color.GRAY);
        titleCounterLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        formPanel.add(titleCounterLabel, gbc);

        // Row 2: Category & Priority Selection
        JPanel optionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 0));
        optionsPanel.add(new JLabel("Category:"));
        optionsPanel.add(categoryCombo);
        optionsPanel.add(Box.createHorizontalStrut(15));
        optionsPanel.add(new JLabel("Priority:"));
        priorityCombo.setSelectedItem(Priority.CRITICAL); // Default to Critical for emergencies
        optionsPanel.add(priorityCombo);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        JLabel metaLabel = new JLabel("Classification:");
        metaLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        formPanel.add(metaLabel, gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        formPanel.add(optionsPanel, gbc);
        gbc.gridwidth = 1;

        // Row 3: Message Text
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0;
        gbc.anchor = GridBagConstraints.NORTHWEST;
        JLabel messageLabel = new JLabel("Message Body:");
        messageLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        formPanel.add(messageLabel, gbc);

        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        JScrollPane scrollPane = new JScrollPane(messageArea);
        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0; gbc.weighty = 1.0;
        gbc.fill = GridBagConstraints.BOTH;
        formPanel.add(scrollPane, gbc);

        gbc.gridx = 2; gbc.gridy = 2; gbc.weightx = 0.0; gbc.weighty = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.SOUTHEAST;
        messageCounterLabel.setForeground(Color.GRAY);
        messageCounterLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        formPanel.add(messageCounterLabel, gbc);

        add(formPanel, BorderLayout.CENTER);

        // Bottom Action and Status Panel
        JPanel bottomPanel = new JPanel(new BorderLayout(10, 10));

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);

        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));

        JPanel statusBox = new JPanel(new BorderLayout(5, 5));
        statusBox.add(progressBar, BorderLayout.NORTH);
        statusBox.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.add(statusBox, BorderLayout.NORTH);

        JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        clearButton.addActionListener(e -> clearForm());

        broadcastButton.setFont(new Font("SansSerif", Font.BOLD, 13));
        broadcastButton.setBackground(new Color(180, 30, 30));
        broadcastButton.setForeground(Color.WHITE);
        broadcastButton.setFocusPainted(false);
        broadcastButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
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
                    titleCounterLabel.setForeground(Color.RED);
                } else {
                    titleCounterLabel.setForeground(Color.GRAY);
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
                    messageCounterLabel.setForeground(Color.RED);
                } else {
                    messageCounterLabel.setForeground(Color.GRAY);
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
        statusLabel.setForeground(new Color(30, 90, 160));
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
        statusLabel.setForeground(new Color(25, 130, 40));
        statusLabel.setText(htmlMessage);
    }

    private void showError(String errorMessage) {
        statusLabel.setForeground(new Color(180, 20, 20));
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

