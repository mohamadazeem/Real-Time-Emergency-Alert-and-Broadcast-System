package edu.ec5205.alert.admin.ui;

import edu.ec5205.alert.admin.network.AdminConnection;
import edu.ec5205.alert.admin.network.MessageListener;
import edu.ec5205.alert.admin.service.AdminAuthService;
import edu.ec5205.alert.admin.service.AlertService;
import edu.ec5205.alert.common.Messages;
import edu.ec5205.alert.common.ProtocolMessage;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Main administrator dashboard providing sidebar navigation between alert creation,
 * connected client rosters, alert monitoring, and history.
 */
public final class DashboardFrame extends JFrame {

    // ── Colors ────────────────────────────────────────────────────────────────
    private static final Color NAV_BG        = new Color(15,  23,  42);
    private static final Color NAV_ACTIVE    = new Color(30,  64, 175);
    private static final Color ACCENT        = new Color(37,  99, 235);
    private static final Color CONTENT_BG    = new Color(248, 250, 252);
    private static final Color CARD_BG       = Color.WHITE;
    private static final Color BORDER        = new Color(226, 232, 240);
    private static final Color TEXT_ON_DARK  = Color.WHITE;
    private static final Color TEXT_SECONDARY = new Color(100, 116, 139);
    private static final Color SUCCESS_COLOR = new Color(16,  185, 129);
    private static final Color DANGER_COLOR  = new Color(239, 68,  68);

    // ── Fields ────────────────────────────────────────────────────────────────
    private final AdminConnection   connection;
    private final AdminAuthService  authService;
    private final AlertService      alertService;

    private final JLabel connectionStatusLabel = new JLabel();

    /** CardLayout panel names */
    private static final String CARD_DASHBOARD  = "dashboard";
    private static final String CARD_TABS       = "tabs";

    private CardLayout cardLayout;
    private JPanel     contentArea;
    private JTabbedPane tabbedPane;
    private DashboardOverviewPanel overviewPanel;

    /** Nav buttons – kept for active-state toggling */
    private JButton[] navButtons;

    // ─────────────────────────────────────────────────────────────────────────

    public DashboardFrame(AdminConnection connection,
                          AdminAuthService authService,
                          AlertService alertService) {
        this.connection  = Objects.requireNonNull(connection,  "connection cannot be null");
        this.authService = Objects.requireNonNull(authService, "authService cannot be null");
        this.alertService = Objects.requireNonNull(alertService, "alertService cannot be null");

        initUI();
        registerConnectionListener();
    }

    // ── UI initialisation ─────────────────────────────────────────────────────

    private void initUI() {
        setTitle("Emergency Alert & Broadcast System — Admin Dashboard");
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 700));
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleLogoutAndExit();
            }
        });

        JPanel root = new JPanel(new BorderLayout());
        root.add(createHeaderPanel(), BorderLayout.NORTH);
        root.add(createSidebar(),     BorderLayout.WEST);
        root.add(createContentArea(), BorderLayout.CENTER);

        setContentPane(root);
        pack();
        setSize(Math.max(getWidth(), 1100), Math.max(getHeight(), 700));
        setLocationRelativeTo(null);
    }

    // ── Header ────────────────────────────────────────────────────────────────

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(NAV_BG);
        header.setPreferredSize(new Dimension(0, 52));
        header.setBorder(new EmptyBorder(0, 20, 0, 20));

        // Left: app title
        JLabel title = new JLabel("🚨  Emergency Alert & Broadcast System");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(TEXT_ON_DARK);
        header.add(title, BorderLayout.WEST);

        // Right: status badge + logout
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
        right.setOpaque(false);

        updateConnectionStatusLabel(true);
        right.add(connectionStatusLabel);

        JButton logoutBtn = new JButton("Logout");
        logoutBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
        logoutBtn.setBackground(new Color(30, 41, 59));
        logoutBtn.setForeground(TEXT_ON_DARK);
        logoutBtn.setFocusPainted(false);
        logoutBtn.setBorder(new CompoundBorder(
                new LineBorder(new Color(51, 65, 85), 1),
                new EmptyBorder(6, 14, 6, 14)
        ));
        logoutBtn.setOpaque(true);
        logoutBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        logoutBtn.addActionListener(e -> handleLogoutAndReturnToLogin());
        right.add(logoutBtn);

        // Vertically centre the right panel
        JPanel rightWrapper = new JPanel(new GridBagLayout());
        rightWrapper.setOpaque(false);
        rightWrapper.add(right);
        header.add(rightWrapper, BorderLayout.EAST);

        return header;
    }

    // ── Sidebar ───────────────────────────────────────────────────────────────

    private JPanel createSidebar() {
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setBackground(NAV_BG);
        sidebar.setPreferredSize(new Dimension(210, 0));

        // ── User info block ───────────────────────────────────────────────────
        JPanel userBlock = new JPanel();
        userBlock.setLayout(new BoxLayout(userBlock, BoxLayout.Y_AXIS));
        userBlock.setBackground(NAV_BG);
        userBlock.setBorder(new EmptyBorder(20, 16, 16, 16));

        JLabel iconLabel = new JLabel("👤");
        iconLabel.setFont(new Font("SansSerif", Font.PLAIN, 28));
        iconLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        String username = authService.getSession().map(s -> s.username()).orElse("Administrator");
        JLabel nameLabel = new JLabel(username);
        nameLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
        nameLabel.setForeground(TEXT_ON_DARK);
        nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel roleLabel = new JLabel("System Administrator");
        roleLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
        roleLabel.setForeground(TEXT_SECONDARY);
        roleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        userBlock.add(iconLabel);
        userBlock.add(Box.createVerticalStrut(6));
        userBlock.add(nameLabel);
        userBlock.add(Box.createVerticalStrut(2));
        userBlock.add(roleLabel);

        // ── Separator ─────────────────────────────────────────────────────────
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(30, 41, 59));
        sep.setBackground(NAV_BG);

        // ── Nav buttons ───────────────────────────────────────────────────────
        String[] labels = {
            "📊  Dashboard",
            "🚨  Create Alert",
            "👥  Connected Clients",
            "📡  Alert Monitoring",
            "📋  Alert History"
        };

        navButtons = new JButton[labels.length];
        JPanel navPanel = new JPanel();
        navPanel.setLayout(new BoxLayout(navPanel, BoxLayout.Y_AXIS));
        navPanel.setBackground(NAV_BG);
        navPanel.setBorder(new EmptyBorder(10, 0, 8, 0));

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            JButton btn = buildNavButton(labels[i]);
            btn.addActionListener(e -> switchCard(idx));
            navButtons[i] = btn;
            navPanel.add(btn);
            navPanel.add(Box.createVerticalStrut(2));
        }

        // ── Logout at bottom ──────────────────────────────────────────────────
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(NAV_BG);
        bottomPanel.setBorder(new MatteBorder(1, 0, 0, 0, new Color(30, 41, 59)));

        JButton sidebarLogout = buildNavButton("⬅  Logout");
        sidebarLogout.addActionListener(e -> handleLogoutAndReturnToLogin());
        bottomPanel.add(sidebarLogout, BorderLayout.CENTER);

        // ── Compose sidebar ───────────────────────────────────────────────────
        JPanel topPart = new JPanel(new BorderLayout());
        topPart.setBackground(NAV_BG);
        topPart.add(userBlock, BorderLayout.NORTH);
        topPart.add(sep,       BorderLayout.CENTER);

        JPanel middlePart = new JPanel(new BorderLayout());
        middlePart.setBackground(NAV_BG);
        middlePart.add(topPart,  BorderLayout.NORTH);
        middlePart.add(navPanel, BorderLayout.CENTER);

        sidebar.add(middlePart,  BorderLayout.CENTER);
        sidebar.add(bottomPanel, BorderLayout.SOUTH);

        // Activate the first button
        setNavActive(0);

        return sidebar;
    }

    private JButton buildNavButton(String label) {
        JButton btn = new JButton(label);
        btn.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btn.setForeground(TEXT_SECONDARY);
        btn.setBackground(NAV_BG);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setHorizontalAlignment(SwingConstants.LEFT);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(new EmptyBorder(10, 20, 10, 20));
        btn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        btn.setPreferredSize(new Dimension(210, 40));
        return btn;
    }

    private void setNavActive(int activeIndex) {
        for (int i = 0; i < navButtons.length; i++) {
            if (i == activeIndex) {
                navButtons[i].setBackground(NAV_ACTIVE);
                navButtons[i].setForeground(TEXT_ON_DARK);
                navButtons[i].setFont(new Font("SansSerif", Font.BOLD, 13));
            } else {
                navButtons[i].setBackground(NAV_BG);
                navButtons[i].setForeground(TEXT_SECONDARY);
                navButtons[i].setFont(new Font("SansSerif", Font.PLAIN, 13));
            }
        }
    }

    private void switchCard(int index) {
        setNavActive(index);
        if (index == 0) {
            cardLayout.show(contentArea, CARD_DASHBOARD);
            if (overviewPanel != null) {
                overviewPanel.refreshData();
            }
        } else {
            tabbedPane.setSelectedIndex(index - 1);
            cardLayout.show(contentArea, CARD_TABS);
        }
    }

    // ── Content Area ──────────────────────────────────────────────────────────

    private JPanel createContentArea() {
        cardLayout  = new CardLayout();
        contentArea = new JPanel(cardLayout);
        contentArea.setBackground(CONTENT_BG);

        tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("SansSerif", Font.BOLD, 12));
        tabbedPane.setBackground(CONTENT_BG);
        tabbedPane.addTab("Create Alert", new CreateAlertPanel(alertService));
        tabbedPane.addTab("Connected Clients", new ConnectedClientsPanel(connection, authService));
        tabbedPane.addTab("Alert Monitoring", new AlertMonitoringPanel(connection, authService));
        tabbedPane.addTab("Alert History", new AlertHistoryPanel(authService));

        tabbedPane.addChangeListener(e -> {
            int selectedTab = tabbedPane.getSelectedIndex();
            if (selectedTab >= 0 && selectedTab < 4) {
                setNavActive(selectedTab + 1);
            }
        });

        String username = authService.getSession().map(s -> s.username()).orElse("Administrator");

        overviewPanel = new DashboardOverviewPanel(connection, authService, username, this::switchCard);
        contentArea.add(overviewPanel, CARD_DASHBOARD);
        contentArea.add(tabbedPane, CARD_TABS);

        cardLayout.show(contentArea, CARD_DASHBOARD);
        overviewPanel.refreshData();
        return contentArea;
    }

    // ── Connection status ─────────────────────────────────────────────────────

    private void updateConnectionStatusLabel(boolean connected) {
        if (connected) {
            connectionStatusLabel.setText("●  Connected  " + connection.host() + ":" + connection.port());
            connectionStatusLabel.setForeground(SUCCESS_COLOR);
        } else {
            connectionStatusLabel.setText("●  Disconnected");
            connectionStatusLabel.setForeground(DANGER_COLOR);
        }
        connectionStatusLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        connectionStatusLabel.setOpaque(false);
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

    // ── Logout helpers ────────────────────────────────────────────────────────

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

    // ═════════════════════════════════════════════════════════════════════════
    // Dashboard Overview Panel with Live Telemetry and Push Updates
    // ═════════════════════════════════════════════════════════════════════════

    private static final class DashboardOverviewPanel extends JPanel {

        private final AdminConnection  connection;
        private final AdminAuthService authService;
        private final String           username;
        private final Consumer<Integer> navigator;

        private final JLabel totalAlertsVal   = new JLabel("0");
        private final JLabel activeClientsVal = new JLabel("0");
        private final JLabel totalAcksVal     = new JLabel("0");
        private final JLabel systemStatusVal  = new JLabel("ONLINE");

        private final JLabel lastUpdatedLabel = new JLabel("Ready");
        private final JProgressBar progressBar = new JProgressBar();
        private final JButton refreshBtn       = new JButton("🔄  Refresh Data");

        private final DefaultTableModel recentAlertsModel = new DefaultTableModel(
                new String[]{"Created At (UTC)", "Priority", "Category", "Title", "Alert ID"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        private final JTable recentAlertsTable = new JTable(recentAlertsModel);

        private boolean isRefreshing = false;

        DashboardOverviewPanel(AdminConnection connection,
                               AdminAuthService authService,
                               String username,
                               Consumer<Integer> navigator) {
            this.connection  = connection;
            this.authService = authService;
            this.username    = username;
            this.navigator   = navigator;

            setBackground(CONTENT_BG);
            setLayout(new BorderLayout(16, 16));
            setBorder(new EmptyBorder(24, 28, 24, 28));

            initUI();
            registerTelemetryListeners();
        }

        private void initUI() {
            // ── Top Header Section ──
            JPanel topHeader = new JPanel(new BorderLayout(12, 6));
            topHeader.setOpaque(false);

            JPanel titleBox = new JPanel(new BorderLayout(2, 2));
            titleBox.setOpaque(false);
            JLabel pageTitle = new JLabel("Emergency Operations Dashboard");
            pageTitle.setFont(new Font("SansSerif", Font.BOLD, 20));
            pageTitle.setForeground(new Color(15, 23, 42));

            JLabel subtitle = new JLabel("Live broadcast telemetry, active receiver monitoring, and recent alerts.");
            subtitle.setFont(new Font("SansSerif", Font.PLAIN, 12));
            subtitle.setForeground(TEXT_SECONDARY);

            titleBox.add(pageTitle, BorderLayout.NORTH);
            titleBox.add(subtitle, BorderLayout.SOUTH);
            topHeader.add(titleBox, BorderLayout.WEST);

            // Right header controls
            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            controls.setOpaque(false);

            progressBar.setIndeterminate(true);
            progressBar.setPreferredSize(new Dimension(80, 16));
            progressBar.setVisible(false);
            controls.add(progressBar);

            lastUpdatedLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            lastUpdatedLabel.setForeground(TEXT_SECONDARY);
            controls.add(lastUpdatedLabel);

            refreshBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
            refreshBtn.setBackground(Color.WHITE);
            refreshBtn.setForeground(ACCENT);
            refreshBtn.setBorder(new CompoundBorder(
                    new LineBorder(BORDER, 1),
                    new EmptyBorder(6, 12, 6, 12)
            ));
            refreshBtn.setFocusPainted(false);
            refreshBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            refreshBtn.addActionListener(e -> refreshData());
            controls.add(refreshBtn);

            topHeader.add(controls, BorderLayout.EAST);
            add(topHeader, BorderLayout.NORTH);

            // ── Main Body: Stat Cards + Activity Table ──
            JPanel body = new JPanel(new BorderLayout(16, 16));
            body.setOpaque(false);

            // Stat Cards Grid (1 row, 4 columns)
            JPanel grid = new JPanel(new GridLayout(1, 4, 14, 0));
            grid.setOpaque(false);

            grid.add(buildStatCard("🚨  BROADCAST ALERTS", totalAlertsVal,   ACCENT,        "Stored in database"));
            grid.add(buildStatCard("👥  CONNECTED CLIENTS", activeClientsVal, SUCCESS_COLOR, "Online terminals"));
            grid.add(buildStatCard("✅  TOTAL ACKS",        totalAcksVal,     new Color(13, 148, 136), "Verified receipts"));
            grid.add(buildStatCard("⚡  SYSTEM STATUS",     systemStatusVal,  SUCCESS_COLOR, "TCP 127.0.0.1:5050"));

            body.add(grid, BorderLayout.NORTH);

            // Recent Alerts Section Card
            JPanel tableCard = new JPanel(new BorderLayout(0, 8));
            tableCard.setBackground(CARD_BG);
            tableCard.setBorder(new CompoundBorder(
                    new LineBorder(BORDER, 1),
                    new EmptyBorder(14, 16, 14, 16)
            ));

            JPanel tableHeader = new JPanel(new BorderLayout());
            tableHeader.setOpaque(false);
            JLabel tableTitle = new JLabel("Recent Emergency Broadcast Activity");
            tableTitle.setFont(new Font("SansSerif", Font.BOLD, 14));
            tableTitle.setForeground(new Color(15, 23, 42));
            tableHeader.add(tableTitle, BorderLayout.WEST);

            JButton newAlertBtn = new JButton("➕  New Broadcast");
            newAlertBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
            newAlertBtn.setBackground(ACCENT);
            newAlertBtn.setForeground(Color.WHITE);
            newAlertBtn.setBorder(new EmptyBorder(5, 12, 5, 12));
            newAlertBtn.setFocusPainted(false);
            newAlertBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            newAlertBtn.addActionListener(e -> {
                if (navigator != null) navigator.accept(1);
            });
            tableHeader.add(newAlertBtn, BorderLayout.EAST);
            tableCard.add(tableHeader, BorderLayout.NORTH);

            // Style table
            Theme.styleTable(recentAlertsTable);
            recentAlertsTable.getColumnModel().getColumn(0).setPreferredWidth(170);
            recentAlertsTable.getColumnModel().getColumn(1).setPreferredWidth(90);
            recentAlertsTable.getColumnModel().getColumn(2).setPreferredWidth(90);
            recentAlertsTable.getColumnModel().getColumn(3).setPreferredWidth(260);
            recentAlertsTable.getColumnModel().getColumn(4).setPreferredWidth(220);

            // Priority badge cell renderer
            recentAlertsTable.getColumnModel().getColumn(1).setCellRenderer(new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                                                               boolean hasFocus, int row, int column) {
                    Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                    if (value != null && !isSelected) {
                        String s = value.toString();
                        if ("CRITICAL".equalsIgnoreCase(s)) {
                            setForeground(DANGER_COLOR);
                            setFont(new Font("SansSerif", Font.BOLD, 12));
                        } else if ("MEDIUM".equalsIgnoreCase(s)) {
                            setForeground(new Color(217, 119, 6));
                            setFont(new Font("SansSerif", Font.BOLD, 12));
                        } else {
                            setForeground(SUCCESS_COLOR);
                            setFont(new Font("SansSerif", Font.BOLD, 12));
                        }
                    }
                    setHorizontalAlignment(SwingConstants.CENTER);
                    return c;
                }
            });

            JScrollPane scrollPane = new JScrollPane(recentAlertsTable);
            scrollPane.setBorder(new LineBorder(BORDER, 1));
            scrollPane.getViewport().setBackground(Color.WHITE);
            tableCard.add(scrollPane, BorderLayout.CENTER);

            body.add(tableCard, BorderLayout.CENTER);
            add(body, BorderLayout.CENTER);
        }

        private JPanel buildStatCard(String title, JLabel valueLabel, Color accentColor, String subtitle) {
            JPanel card = new JPanel();
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBackground(CARD_BG);
            card.setBorder(new CompoundBorder(
                    new LineBorder(BORDER, 1),
                    new EmptyBorder(14, 16, 14, 16)
            ));

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(new Font("SansSerif", Font.BOLD, 11));
            titleLabel.setForeground(TEXT_SECONDARY);
            titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            valueLabel.setFont(new Font("SansSerif", Font.BOLD, 26));
            valueLabel.setForeground(accentColor);
            valueLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            JLabel subLabel = new JLabel(subtitle);
            subLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            subLabel.setForeground(new Color(148, 163, 184));
            subLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

            card.add(titleLabel);
            card.add(Box.createVerticalStrut(6));
            card.add(valueLabel);
            card.add(Box.createVerticalStrut(4));
            card.add(subLabel);

            return card;
        }

        private void registerTelemetryListeners() {
            connection.addListener(new MessageListener() {
                @Override
                public void onMessage(ProtocolMessage message) {
                    if (message instanceof Messages.ClientList list) {
                        SwingUtilities.invokeLater(() -> {
                            activeClientsVal.setText(String.valueOf(list.clients().size()));
                            lastUpdatedLabel.setText("Live client update: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                        });
                    } else if (message instanceof Messages.AckStatus) {
                        SwingUtilities.invokeLater(() -> {
                            try {
                                int current = Integer.parseInt(totalAcksVal.getText().trim());
                                totalAcksVal.setText(String.valueOf(current + 1));
                            } catch (Exception ignored) {}
                        });
                    } else if (message instanceof Messages.AlertAccepted) {
                        SwingUtilities.invokeLater(() -> refreshData());
                    }
                }

                @Override
                public void onClosed() {
                    SwingUtilities.invokeLater(() -> {
                        systemStatusVal.setText("OFFLINE");
                        systemStatusVal.setForeground(DANGER_COLOR);
                    });
                }

                @Override
                public void onError(Throwable error) {
                    SwingUtilities.invokeLater(() -> {
                        systemStatusVal.setText("OFFLINE");
                        systemStatusVal.setForeground(DANGER_COLOR);
                    });
                }
            });
        }

        public void refreshData() {
            if (isRefreshing) return;
            isRefreshing = true;
            progressBar.setVisible(true);
            refreshBtn.setEnabled(false);
            lastUpdatedLabel.setText("Updating telemetry...");

            record TelemetryData(List<Messages.Alert> alerts, List<Messages.ClientInfo> clients, int totalAcks) {}

            SwingWorker<TelemetryData, Void> worker = new SwingWorker<>() {
                @Override
                protected TelemetryData doInBackground() {
                    List<Messages.Alert> alerts = List.of();
                    List<Messages.ClientInfo> clients = List.of();
                    int acksCount = 0;

                    try {
                        if (authService.isAuthenticated()) {
                            alerts = authService.requestHistory(20, Duration.ofSeconds(3));
                        }
                    } catch (Exception ignored) {}

                    try {
                        if (authService.isAuthenticated()) {
                            clients = authService.requestConnectedClients(Duration.ofSeconds(3));
                        }
                    } catch (Exception ignored) {}

                    try {
                        if (authService.isAuthenticated() && !alerts.isEmpty()) {
                            // Sum ACKs from latest alerts
                            for (int i = 0; i < Math.min(alerts.size(), 5); i++) {
                                try {
                                    var acks = authService.requestAcknowledgements(alerts.get(i).id(), Duration.ofSeconds(2));
                                    acksCount += acks.size();
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}

                    return new TelemetryData(alerts, clients, acksCount);
                }

                @Override
                protected void done() {
                    isRefreshing = false;
                    progressBar.setVisible(false);
                    refreshBtn.setEnabled(true);
                    try {
                        TelemetryData data = get();
                        totalAlertsVal.setText(String.valueOf(data.alerts().size()));
                        activeClientsVal.setText(String.valueOf(data.clients().size()));
                        totalAcksVal.setText(String.valueOf(data.totalAcks()));

                        boolean online = connection.isConnected();
                        systemStatusVal.setText(online ? "ONLINE" : "OFFLINE");
                        systemStatusVal.setForeground(online ? SUCCESS_COLOR : DANGER_COLOR);

                        recentAlertsModel.setRowCount(0);
                        for (Messages.Alert a : data.alerts()) {
                            recentAlertsModel.addRow(new Object[]{
                                    a.createdAt(),
                                    a.priority(),
                                    a.category(),
                                    a.title(),
                                    a.id()
                            });
                        }
                        lastUpdatedLabel.setText("Updated: " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                    } catch (Exception ex) {
                        lastUpdatedLabel.setText("Telemetry sync: " + ex.getMessage());
                    }
                }
            };
            worker.execute();
        }
    }
}
