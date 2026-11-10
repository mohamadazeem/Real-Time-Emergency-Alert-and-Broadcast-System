package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.admin.service.AdminAuthService.LoginOutcome;
import edu.ec5205.alert.admin.service.AlertService;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.net.ConnectException;

/**
 * Swing login window for the Administrator application.
 * Manages server connection setup, credential input, and authentication state.
 */
public final class LoginFrame extends JFrame {

    private final JTextField hostField = new JTextField(AdminConnection.DEFAULT_HOST, 15);
    private final JTextField portField = new JTextField(String.valueOf(AdminConnection.DEFAULT_PORT), 6);
    private final JTextField usernameField = new JTextField("admin", 15);
    private final JPasswordField passwordField = new JPasswordField(15);

    private final JButton loginButton = new JButton("Connect & Authenticate");
    private final JLabel statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();

    public LoginFrame() {
        initUI();
    }

    private void initUI() {
        setTitle("Emergency Alert System — Administrator Login");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        JPanel mainPanel = new JPanel(new BorderLayout(15, 15));
        mainPanel.setBorder(new EmptyBorder(25, 30, 25, 30));

        // Header Banner
        JPanel headerPanel = new JPanel(new GridLayout(2, 1, 4, 4));
        JLabel titleLabel = new JLabel("Admin Control Center");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 18));
        titleLabel.setForeground(new Color(30, 45, 65));

        JLabel subtitleLabel = new JLabel("Real-Time Emergency Alert & Broadcast System");
        subtitleLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        subtitleLabel.setForeground(new Color(100, 110, 125));

        headerPanel.add(titleLabel);
        headerPanel.add(subtitleLabel);
        mainPanel.add(headerPanel, BorderLayout.NORTH);

        // Center Form Panel
        JPanel formPanel = new JPanel(new GridBagLayout());
        formPanel.setBorder(new CompoundBorder(
                new LineBorder(new Color(220, 225, 230)),
                new EmptyBorder(15, 15, 15, 15)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(6, 6, 6, 6);

        // Server IP & Port
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Server Host:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        formPanel.add(hostField, gbc);

        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Server Port:"), gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
        formPanel.add(portField, gbc);

        // Username & Password
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Admin User:"), gbc);

        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0;
        formPanel.add(usernameField, gbc);

        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0;
        formPanel.add(new JLabel("Password:"), gbc);

        gbc.gridx = 1; gbc.gridy = 3; gbc.weightx = 1.0;
        formPanel.add(passwordField, gbc);

        mainPanel.add(formPanel, BorderLayout.CENTER);

        // Bottom Actions and Status
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 8));

        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JPanel statusBox = new JPanel(new BorderLayout(4, 4));
        statusBox.add(progressBar, BorderLayout.NORTH);
        statusBox.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.add(statusBox, BorderLayout.NORTH);

        loginButton.setFont(new Font("SansSerif", Font.BOLD, 13));
        loginButton.setBackground(new Color(40, 80, 140));
        loginButton.setForeground(Color.WHITE);
        loginButton.setFocusPainted(false);
        loginButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        loginButton.addActionListener(e -> attemptLogin());
        getRootPane().setDefaultButton(loginButton);

        bottomPanel.add(loginButton, BorderLayout.SOUTH);

        mainPanel.add(bottomPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);
        pack();
        setLocationRelativeTo(null);
    }

    private void attemptLogin() {
        String host = hostField.getText().trim();
        String portStr = portField.getText().trim();
        String username = usernameField.getText().trim();
        char[] passwordChars = passwordField.getPassword();
        String password = new String(passwordChars);

        // 1. Validate required fields
        if (host.isEmpty()) {
            showError("Server host cannot be empty.");
            hostField.requestFocusInWindow();
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) {
                showError("Port must be between 1 and 65535.");
                portField.requestFocusInWindow();
                return;
            }
        } catch (NumberFormatException e) {
            showError("Invalid port number format.");
            portField.requestFocusInWindow();
            return;
        }

        if (username.isEmpty()) {
            showError("Username cannot be empty.");
            usernameField.requestFocusInWindow();
            return;
        }

        if (password.isEmpty()) {
            showError("Password cannot be empty.");
            passwordField.requestFocusInWindow();
            return;
        }

        // 2. Prepare UI for connecting state
        setFormEnabled(false);
        progressBar.setVisible(true);
        statusLabel.setForeground(new Color(40, 90, 160));
        statusLabel.setText("Connecting to " + host + ":" + port + "...");

        // 3. Run network I/O in background thread using SwingWorker
        SwingWorker<LoginOutcome, Void> worker = new SwingWorker<>() {
            private AdminConnection connection;
            private AdminAuthService authService;

            @Override
            protected LoginOutcome doInBackground() {
                try {
                    connection = new AdminConnection(host, port);
                    connection.connect();
                    authService = new AdminAuthService(connection);
                    return authService.login(username, password);
                } catch (ConnectException e) {
                    if (connection != null) connection.close();
                    return LoginOutcome.failure("Connection refused: Ensure server is running on " + host + ":" + port);
                } catch (Exception e) {
                    if (connection != null) connection.close();
                    return LoginOutcome.failure("Connection error: " + e.getMessage());
                }
            }

            @Override
            protected void done() {
                progressBar.setVisible(false);
                try {
                    LoginOutcome outcome = get();
                    if (outcome.successful()) {
                        statusLabel.setForeground(new Color(25, 120, 35));
                        statusLabel.setText("Login successful! Launching dashboard...");

                        AlertService alertService = new AlertService(connection, authService);
                        DashboardFrame dashboard = new DashboardFrame(connection, authService, alertService);
                        dashboard.setVisible(true);
                        dispose(); // Close login window
                    } else {
                        setFormEnabled(true);
                        showError(outcome.message());
                        if (connection != null) {
                            connection.close();
                        }
                    }
                } catch (Exception e) {
                    setFormEnabled(true);
                    showError("Unexpected failure: " + e.getMessage());
                    if (connection != null) {
                        connection.close();
                    }
                }
            }
        };
        worker.execute();
    }

    private void setFormEnabled(boolean enabled) {
        loginButton.setEnabled(enabled);
        hostField.setEnabled(enabled);
        portField.setEnabled(enabled);
        usernameField.setEnabled(enabled);
        passwordField.setEnabled(enabled);
    }

    private void showError(String message) {
        statusLabel.setForeground(new Color(180, 25, 25));
        statusLabel.setText(message);
    }
}

