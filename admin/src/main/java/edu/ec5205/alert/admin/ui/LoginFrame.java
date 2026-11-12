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

    // ── Design tokens (inline — no Theme.java dependency) ──────────────────
    private static final Color NAV_BG       = new Color(15,  23,  42);
    private static final Color ACCENT       = new Color(37,  99,  235);
    private static final Color CONTENT_BG   = new Color(248, 250, 252);
    private static final Color BORDER       = new Color(226, 232, 240);
    private static final Color TEXT_SECONDARY = new Color(100, 116, 139);
    private static final Color DANGER       = new Color(239, 68,  68);
    private static final Color SUCCESS      = new Color(16,  185, 129);

    // ── Form fields (preserve existing field names & types) ────────────────
    private final JTextField     hostField     = new JTextField(AdminConnection.DEFAULT_HOST, 15);
    private final JTextField     portField     = new JTextField(String.valueOf(AdminConnection.DEFAULT_PORT), 6);
    private final JTextField     usernameField = new JTextField("admin", 15);
    private final JPasswordField passwordField = new JPasswordField(15);

    private final JButton   loginButton = new JButton("Connect & Authenticate");
    private final JLabel    statusLabel = new JLabel(" ");
    private final JProgressBar progressBar = new JProgressBar();

    // ── Password show/hide toggle ──────────────────────────────────────────
    private final JButton eyeButton = new JButton("\uD83D\uDC41"); // 👁
    private boolean passwordVisible = false;

    public LoginFrame() {
        initUI();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  UI CONSTRUCTION
    // ══════════════════════════════════════════════════════════════════════

    private void initUI() {
        setTitle("Emergency Alert System — Administrator Login");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));

        // Root: two panels side-by-side
        JPanel root = new JPanel(new BorderLayout());
        root.add(buildLeftPanel(),  BorderLayout.WEST);
        root.add(buildRightPanel(), BorderLayout.CENTER);

        setContentPane(root);
        pack();
        setLocationRelativeTo(null);
    }

    // ── LEFT PANEL (40 %) ─────────────────────────────────────────────────
    private JPanel buildLeftPanel() {
        JPanel panel = new JPanel() {
            @Override
            public Dimension getPreferredSize() {
                // Always 40 % of window minimum width
                Dimension parent = LoginFrame.this.getSize();
                int w = parent.width > 0 ? (int)(parent.width * 0.40) : 360;
                return new Dimension(Math.max(w, 320), 600);
            }
        };
        panel.setBackground(NAV_BG);
        panel.setLayout(new BorderLayout());

        // ── Centre branding ──
        JPanel centre = new JPanel();
        centre.setOpaque(false);
        centre.setLayout(new BoxLayout(centre, BoxLayout.Y_AXIS));
        centre.setBorder(new EmptyBorder(0, 30, 0, 30));

        JLabel icon = new JLabel("\uD83D\uDEA8", SwingConstants.CENTER); // 🚨
        icon.setFont(new Font("SansSerif", Font.PLAIN, 64));
        icon.setForeground(Color.WHITE);
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel appName = new JLabel("Emergency Alert System", SwingConstants.CENTER);
        appName.setFont(new Font("SansSerif", Font.BOLD, 22));
        appName.setForeground(Color.WHITE);
        appName.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel tagline = new JLabel("Real-Time Broadcast & Management", SwingConstants.CENTER);
        tagline.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tagline.setForeground(new Color(148, 163, 184));
        tagline.setAlignmentX(Component.CENTER_ALIGNMENT);

        centre.add(Box.createVerticalGlue());
        centre.add(icon);
        centre.add(Box.createVerticalStrut(16));
        centre.add(appName);
        centre.add(Box.createVerticalStrut(8));
        centre.add(tagline);
        centre.add(Box.createVerticalGlue());

        panel.add(centre, BorderLayout.CENTER);
        return panel;
    }

    // ── RIGHT PANEL (60 %) ────────────────────────────────────────────────
    private JPanel buildRightPanel() {
        JPanel outer = new JPanel(new GridBagLayout());
        outer.setBackground(CONTENT_BG);

        JPanel form = new JPanel();
        form.setBackground(Color.WHITE);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1),
                new EmptyBorder(36, 40, 36, 40)
        ));
        form.setMaximumSize(new Dimension(440, Integer.MAX_VALUE));

        // Heading
        JLabel heading = new JLabel("Administrator Login");
        heading.setFont(new Font("SansSerif", Font.BOLD, 24));
        heading.setForeground(NAV_BG);
        heading.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel subtitle = new JLabel("Sign in to manage emergency broadcasts");
        subtitle.setFont(new Font("SansSerif", Font.PLAIN, 13));
        subtitle.setForeground(TEXT_SECONDARY);
        subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);

        form.add(heading);
        form.add(Box.createVerticalStrut(6));
        form.add(subtitle);
        form.add(Box.createVerticalStrut(28));

        // Fields
        form.add(labeledField("Server Host", hostField, "127.0.0.1"));
        form.add(Box.createVerticalStrut(14));
        form.add(labeledField("Server Port", portField, "5050"));
        form.add(Box.createVerticalStrut(14));
        form.add(labeledField("Username", usernameField, "classroom-admin"));
        form.add(Box.createVerticalStrut(14));
        form.add(buildPasswordRow());
        form.add(Box.createVerticalStrut(24));

        // Progress bar
        progressBar.setIndeterminate(true);
        progressBar.setVisible(false);
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 4));
        progressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(progressBar);
        form.add(Box.createVerticalStrut(10));

        // Login button
        styleLoginButton();
        loginButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        loginButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        loginButton.addActionListener(e -> attemptLogin());
        getRootPane().setDefaultButton(loginButton);
        form.add(loginButton);
        form.add(Box.createVerticalStrut(10));

        // Status label
        statusLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        statusLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(statusLabel);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.BOTH;
        gbc.insets = new Insets(40, 60, 40, 60);
        gbc.weightx = 1;
        gbc.weighty = 1;
        outer.add(form, gbc);

        return outer;
    }

    /** Creates a label + field stacked vertically, full-width. */
    private JPanel labeledField(String labelText, JTextField field, String placeholder) {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(labelText);
        label.setFont(new Font("SansSerif", Font.BOLD, 12));
        label.setForeground(NAV_BG);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        styleTextField(field);
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Placeholder hint via tool-tip (JTextField has no built-in placeholder)
        if (field.getText().trim().isEmpty()) {
            field.setToolTipText(placeholder);
        }

        panel.add(label);
        panel.add(Box.createVerticalStrut(4));
        panel.add(field);
        return panel;
    }

    /** Password row: label above, then field + toggle side-by-side. */
    private JPanel buildPasswordRow() {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel("Password");
        label.setFont(new Font("SansSerif", Font.BOLD, 12));
        label.setForeground(NAV_BG);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        // Field + toggle in a row
        JPanel fieldRow = new JPanel(new BorderLayout(4, 0));
        fieldRow.setOpaque(false);
        fieldRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        fieldRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));

        stylePasswordField(passwordField);
        styleEyeButton();

        fieldRow.add(passwordField, BorderLayout.CENTER);
        fieldRow.add(eyeButton,     BorderLayout.EAST);

        panel.add(label);
        panel.add(Box.createVerticalStrut(4));
        panel.add(fieldRow);
        return panel;
    }

    // ── Styling helpers ───────────────────────────────────────────────────

    private void styleTextField(JTextField field) {
        field.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1),
                new EmptyBorder(8, 10, 8, 10)
        ));
        field.setFont(new Font("SansSerif", Font.PLAIN, 13));
    }

    private void stylePasswordField(JPasswordField field) {
        field.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1),
                new EmptyBorder(8, 10, 8, 10)
        ));
        field.setFont(new Font("SansSerif", Font.PLAIN, 13));
    }

    private void styleLoginButton() {
        loginButton.setFont(new Font("SansSerif", Font.BOLD, 14));
        loginButton.setBackground(ACCENT);
        loginButton.setForeground(Color.WHITE);
        loginButton.setFocusPainted(false);
        loginButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        loginButton.setBorder(new EmptyBorder(10, 20, 10, 20));
        loginButton.setOpaque(true);
    }

    private void styleEyeButton() {
        eyeButton.setFont(new Font("SansSerif", Font.PLAIN, 14));
        eyeButton.setFocusPainted(false);
        eyeButton.setCursor(new Cursor(Cursor.HAND_CURSOR));
        eyeButton.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1),
                new EmptyBorder(4, 8, 4, 8)
        ));
        eyeButton.setBackground(Color.WHITE);
        eyeButton.setOpaque(true);
        eyeButton.setToolTipText("Show / hide password");
        eyeButton.addActionListener(e -> togglePasswordVisibility());
    }

    private void togglePasswordVisibility() {
        passwordVisible = !passwordVisible;
        if (passwordVisible) {
            passwordField.setEchoChar((char) 0);   // reveal
            eyeButton.setText("\uD83D\uDD12");      // 🔒
        } else {
            passwordField.setEchoChar('*');         // hide
            eyeButton.setText("\uD83D\uDC41");      // 👁
        }
        passwordField.requestFocusInWindow();
    }

    // ══════════════════════════════════════════════════════════════════════
    //  LOGIN LOGIC  (unchanged from original)
    // ══════════════════════════════════════════════════════════════════════

    private void attemptLogin() {
        String host     = hostField.getText().trim();
        String portStr  = portField.getText().trim();
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
        statusLabel.setForeground(ACCENT);
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
                        statusLabel.setForeground(SUCCESS);
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
        eyeButton.setEnabled(enabled);
    }

    private void showError(String message) {
        statusLabel.setForeground(DANGER);
        statusLabel.setText(message);
    }
}
