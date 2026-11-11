package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.network.MessageListener;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.admin.service.AlertService;
import edu.ec5205.alert.common.ProtocolMessage;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;

/**
 * Main administrator dashboard providing navigation between alert creation,
 * connected client rosters, alert monitoring, and history.
 */
public final class DashboardFrame extends JFrame {

    private final AdminConnection connection;
    private final AdminAuthService authService;
    private final AlertService alertService;

    private final JLabel connectionStatusLabel = new JLabel();

    public DashboardFrame(AdminConnection connection, AdminAuthService authService, AlertService alertService) {
        this.connection = Objects.requireNonNull(connection, "connection cannot be null");
        this.authService = Objects.requireNonNull(authService, "authService cannot be null");
        this.alertService = Objects.requireNonNull(alertService, "alertService cannot be null");

        initUI();
        registerConnectionListener();
    }

    private void initUI() {
        setTitle("Emergency Alert & Broadcast System — Admin Dashboard");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(880, 620));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleLogoutAndExit();
            }
        });

        JPanel rootPanel = new JPanel(new BorderLayout());

        // Header Panel
        rootPanel.add(createHeaderPanel(), BorderLayout.NORTH);

        // Tabbed Navigation
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("SansSerif", Font.PLAIN, 13));
        tabbedPane.setBorder(new EmptyBorder(5, 10, 10, 10));

        tabbedPane.addTab("Create Alert", new CreateAlertPanel(alertService));
        tabbedPane.addTab("Connected Clients", new ConnectedClientsPanel(connection, authService));
        tabbedPane.addTab("Alert Monitoring", new AlertMonitoringPanel(connection, authService));
        tabbedPane.addTab("Alert History", new AlertHistoryPanel(authService));

        rootPanel.add(tabbedPane, BorderLayout.CENTER);

        setContentPane(rootPanel);
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(15, 10));
        header.setBackground(new Color(35, 45, 60));
        header.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, new Color(20, 25, 35)),
                new EmptyBorder(12, 20, 12, 20)
        ));

        // Title and Admin identity
        JPanel leftPanel = new JPanel(new GridLayout(2, 1, 2, 2));
        leftPanel.setOpaque(false);

        JLabel appTitle = new JLabel("Emergency Alert & Broadcast System");
        appTitle.setFont(new Font("SansSerif", Font.BOLD, 16));
        appTitle.setForeground(Color.WHITE);

        String username = authService.getSession().map(s -> s.username()).orElse("Unknown");
        JLabel userLabel = new JLabel("Logged in as: " + username + " (Administrator)");
        userLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        userLabel.setForeground(new Color(180, 200, 220));

        leftPanel.add(appTitle);
        leftPanel.add(userLabel);
        header.add(leftPanel, BorderLayout.WEST);

        // Status pill & Logout
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 5));
        rightPanel.setOpaque(false);

        updateConnectionStatusLabel(true);
        rightPanel.add(connectionStatusLabel);

        JButton logoutButton = new JButton("Logout");
        logoutButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        logoutButton.setFocusPainted(false);
        logoutButton.setBackground(new Color(70, 85, 105));
        logoutButton.setForeground(Color.WHITE);
        logoutButton.addActionListener(e -> handleLogoutAndReturnToLogin());
        rightPanel.add(logoutButton);

        header.add(rightPanel, BorderLayout.EAST);
        return header;
    }

    private void updateConnectionStatusLabel(boolean connected) {
        if (connected) {
            connectionStatusLabel.setText(" Connected: " + connection.host() + ":" + connection.port() + " ");
            connectionStatusLabel.setOpaque(true);
            connectionStatusLabel.setBackground(new Color(25, 110, 45));
            connectionStatusLabel.setForeground(Color.WHITE);
            connectionStatusLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
            connectionStatusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        } else {
            connectionStatusLabel.setText(" Disconnected ");
            connectionStatusLabel.setOpaque(true);
            connectionStatusLabel.setBackground(new Color(160, 30, 30));
            connectionStatusLabel.setForeground(Color.WHITE);
            connectionStatusLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
            connectionStatusLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        }
    }

    private void registerConnectionListener() {
        connection.addListener(new MessageListener() {
            @Override
            public void onMessage(ProtocolMessage message) {}

            @Override
            public void onClosed() {
                SwingUtilities.invokeLater(() -> updateConnectionStatusLabel(false));
            }

            @Override
            public void onError(Throwable error) {
                SwingUtilities.invokeLater(() -> updateConnectionStatusLabel(false));
            }
        });
    }

    private void handleLogoutAndReturnToLogin() {
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Are you sure you want to log out and close the active session?",
                "Confirm Logout",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (confirm == JOptionPane.YES_OPTION) {
            authService.logout();
            connection.close();
            dispose();
            SwingUtilities.invokeLater(() -> {
                LoginFrame loginFrame = new LoginFrame();
                loginFrame.setVisible(true);
            });
        }
    }

    private void handleLogoutAndExit() {
        int confirm = JOptionPane.showConfirmDialog(
                this,
                "Do you want to disconnect and exit the Admin application?",
                "Exit Application",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE
        );
        if (confirm == JOptionPane.YES_OPTION) {
            authService.logout();
            connection.close();
            dispose();
            System.exit(0);
        }
    }
}

