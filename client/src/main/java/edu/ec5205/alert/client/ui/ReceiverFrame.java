package edu.ec5205.alert.client.ui;

import edu.ec5205.alert.client.model.*;
import edu.ec5205.alert.client.service.ReceiverController;
import edu.ec5205.alert.client.service.ReceiverController.*;
import edu.ec5205.alert.common.Priority;

import javax.swing.*;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;

/**
 * Desktop emergency alert receiver terminal.
 * Modernized UI with clear visual hierarchy, priority alert highlighting, and robust state feedback.
 * All control actions delegate network operations to ReceiverController off the EDT.
 */
public final class ReceiverFrame extends JFrame {

    // ── Design Tokens ─────────────────────────────────────────────────────────
    private static final Color NAV_BG         = new Color(15,  23,  42);
    private static final Color ACCENT         = new Color(37,  99,  235);
    private static final Color CONTENT_BG     = new Color(248, 250, 252);
    private static final Color CARD_BG        = Color.WHITE;
    private static final Color BORDER         = new Color(226, 232, 240);
    private static final Color TEXT_PRIMARY   = new Color(15,  23,  42);
    private static final Color TEXT_SECONDARY = new Color(100, 116, 139);
    private static final Color SUCCESS_COLOR  = new Color(16,  185, 129);
    private static final Color DANGER_COLOR   = new Color(239, 68,  68);
    private static final Color WARNING_COLOR  = new Color(245, 158, 11);

    private static final Color CRITICAL_BG    = new Color(254, 226, 226);
    private static final Color CRITICAL_FG    = new Color(185, 28,  28);
    private static final Color MEDIUM_BG      = new Color(255, 237, 213);
    private static final Color MEDIUM_FG      = new Color(180, 83,  9);
    private static final Color LOW_BG         = new Color(220, 252, 231);
    private static final Color LOW_FG         = new Color(21,  128, 61);

    // ── Existing Fields (strictly preserved) ──────────────────────────────────
    private final JTextField host;
    private final JTextField port;
    private final JTextField name;
    private final JButton connect = new JButton("Connect");
    private final JButton disconnect = new JButton("Disconnect");
    private final JButton refresh = new JButton("Refresh History");
    private final JButton acknowledge = new JButton("Acknowledge");
    private final JLabel connection = new JLabel("DISCONNECTED");
    private final JLabel identity = new JLabel("Identity will be loaded when you connect.");
    private final JLabel priority = new JLabel("Select an alert");
    private final JLabel ackStatus = new JLabel(" ");
    private final JTextArea details = new JTextArea();
    private final JTextArea notice = new JTextArea(2, 60);

    private final DefaultTableModel model = new DefaultTableModel(
            new String[]{"Title", "Priority", "Category", "Created (UTC)", "Acknowledgement"}, 0) {
        @Override public boolean isCellEditable(int row, int col) { return false; }
    };
    private final JTable alerts = new JTable(model);
    private final ReceiverController controller;
    private Snapshot snapshot;
    private boolean rendering;

    public ReceiverFrame(String serverHost, int serverPort, String displayName, Path identityPath) {
        super("Emergency Alert Receiver — " + displayName);
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Create receiver on EDT.");

        host = new JTextField(serverHost, 14);
        port = new JTextField(Integer.toString(serverPort), 5);
        name = new JTextField(displayName, 14);
        controller = new ReceiverController(identityPath, this::render);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(1000, 700));
        setSize(1100, 750);
        setLocationByPlatform(true);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(CONTENT_BG);

        // 1. Top Header & Connection Bar
        JPanel topContainer = new JPanel(new BorderLayout(0, 0));
        topContainer.add(createHeaderPanel(displayName), BorderLayout.NORTH);
        topContainer.add(createConnectionPanel(identityPath), BorderLayout.SOUTH);
        root.add(topContainer, BorderLayout.NORTH);

        // 2. Alert Table Configuration
        alerts.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        alerts.setRowHeight(34);
        alerts.setFillsViewportHeight(true);
        alerts.setShowHorizontalLines(true);
        alerts.setShowVerticalLines(false);
        alerts.setGridColor(BORDER);
        alerts.setIntercellSpacing(new Dimension(0, 1));
        alerts.setSelectionBackground(ACCENT);
        alerts.setSelectionForeground(Color.WHITE);

        JTableHeader th = alerts.getTableHeader();
        th.setFont(new Font("SansSerif", Font.BOLD, 12));
        th.setBackground(NAV_BG);
        th.setForeground(Color.WHITE);
        th.setPreferredSize(new Dimension(0, 36));
        th.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        th.setReorderingAllowed(false);

        alerts.getColumnModel().getColumn(0).setPreferredWidth(260);
        alerts.getColumnModel().getColumn(1).setPreferredWidth(90);
        alerts.getColumnModel().getColumn(2).setPreferredWidth(90);
        alerts.getColumnModel().getColumn(3).setPreferredWidth(210);
        alerts.getColumnModel().getColumn(4).setPreferredWidth(140);

        alerts.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                           boolean focus, int row, int column) {
                Component cell = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                if (snapshot != null && row >= 0 && row < snapshot.alerts().size()) {
                    Priority p = snapshot.alerts().get(row).alert().priority();
                    if (!selected) {
                        if (p == Priority.CRITICAL) {
                            cell.setBackground(CRITICAL_BG);
                            cell.setForeground(CRITICAL_FG);
                            cell.setFont(cell.getFont().deriveFont(Font.BOLD));
                        } else if (p == Priority.MEDIUM) {
                            cell.setBackground(MEDIUM_BG);
                            cell.setForeground(MEDIUM_FG);
                            cell.setFont(cell.getFont().deriveFont(Font.PLAIN));
                        } else {
                            cell.setBackground(row % 2 == 0 ? Color.WHITE : CONTENT_BG);
                            cell.setForeground(TEXT_PRIMARY);
                            cell.setFont(cell.getFont().deriveFont(Font.PLAIN));
                        }
                    } else {
                        cell.setFont(cell.getFont().deriveFont(p == Priority.CRITICAL ? Font.BOLD : Font.PLAIN));
                    }
                }
                return cell;
            }
        });

        alerts.getSelectionModel().addListSelectionListener(event -> { if (!rendering) showSelection(); });

        // 3. Alert Detail Panel (Split bottom)
        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(new Font("SansSerif", Font.PLAIN, 13));
        details.setMargin(new Insets(12, 14, 12, 14));
        details.setBackground(Color.WHITE);

        JPanel alertDetails = new JPanel(new BorderLayout(0, 0));
        alertDetails.setBackground(Color.WHITE);
        alertDetails.setBorder(new LineBorder(BORDER, 1));

        priority.setOpaque(true);
        priority.setBorder(new EmptyBorder(10, 16, 10, 16));
        priority.setFont(new Font("SansSerif", Font.BOLD, 13));
        alertDetails.add(priority, BorderLayout.NORTH);

        JScrollPane detailsScroll = new JScrollPane(details);
        detailsScroll.setBorder(new MatteBorder(1, 0, 1, 0, BORDER));
        alertDetails.add(detailsScroll, BorderLayout.CENTER);

        // Actions Bar
        JPanel actions = new JPanel(new BorderLayout(10, 0));
        actions.setBackground(Color.WHITE);
        actions.setBorder(new EmptyBorder(10, 16, 10, 16));

        ackStatus.setFont(new Font("SansSerif", Font.BOLD, 12));
        actions.add(ackStatus, BorderLayout.WEST);

        JPanel buttonBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        buttonBox.setOpaque(false);

        refresh.setFont(new Font("SansSerif", Font.BOLD, 12));
        refresh.setBackground(Color.WHITE);
        refresh.setForeground(ACCENT);
        refresh.setBorder(new CompoundBorder(
                new LineBorder(ACCENT, 1),
                new EmptyBorder(8, 16, 8, 16)
        ));
        refresh.setFocusPainted(false);
        refresh.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        acknowledge.setFont(new Font("SansSerif", Font.BOLD, 12));
        acknowledge.setBackground(SUCCESS_COLOR);
        acknowledge.setForeground(Color.WHITE);
        acknowledge.setBorder(new EmptyBorder(8, 20, 8, 20));
        acknowledge.setFocusPainted(false);
        acknowledge.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        buttonBox.add(refresh);
        buttonBox.add(acknowledge);
        actions.add(buttonBox, BorderLayout.EAST);

        alertDetails.add(actions, BorderLayout.SOUTH);

        // Center Split Pane
        JPanel tableWrapper = new JPanel(new BorderLayout());
        tableWrapper.setBackground(CARD_BG);
        tableWrapper.setBorder(new LineBorder(BORDER, 1));
        tableWrapper.add(new JScrollPane(alerts), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableWrapper, alertDetails);
        split.setResizeWeight(0.52);
        split.setBorder(new EmptyBorder(12, 18, 8, 18));
        split.setBackground(CONTENT_BG);
        root.add(split, BorderLayout.CENTER);

        // Bottom Notice / Status Line
        notice.setEditable(false);
        notice.setFocusable(false);
        notice.setLineWrap(true);
        notice.setWrapStyleWord(true);
        notice.setFont(new Font("SansSerif", Font.PLAIN, 12));
        notice.setForeground(TEXT_SECONDARY);
        notice.setBackground(CONTENT_BG);
        notice.setBorder(new EmptyBorder(6, 20, 10, 20));
        root.add(notice, BorderLayout.SOUTH);

        setContentPane(root);

        // Listeners
        connect.addActionListener(event -> {
            try {
                controller.connect(host.getText(), Integer.parseInt(port.getText().trim()), name.getText());
            } catch (NumberFormatException e) {
                notice.setText("Port must be an integer from 1 to 65535.");
            }
        });
        disconnect.addActionListener(event -> controller.disconnect());
        refresh.addActionListener(event -> controller.requestHistory());
        acknowledge.addActionListener(event -> {
            AlertView selected = selected();
            if (selected != null) controller.acknowledge(selected.alert().id());
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent event) { dispose(); }
        });

        render(controller.snapshot());
    }

    private JPanel createHeaderPanel(String displayName) {
        JPanel header = new JPanel(new BorderLayout(15, 0));
        header.setBackground(NAV_BG);
        header.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, new Color(30, 41, 59)),
                new EmptyBorder(12, 20, 12, 20)
        ));

        // Left Branding
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        left.setOpaque(false);
        JLabel logo = new JLabel("🚨");
        logo.setFont(new Font("SansSerif", Font.PLAIN, 20));

        JLabel title = new JLabel("Emergency Alert Receiver");
        title.setFont(new Font("SansSerif", Font.BOLD, 16));
        title.setForeground(Color.WHITE);

        JLabel terminalTag = new JLabel("• " + displayName);
        terminalTag.setFont(new Font("SansSerif", Font.PLAIN, 13));
        terminalTag.setForeground(new Color(148, 163, 184));

        left.add(logo);
        left.add(title);
        left.add(terminalTag);
        header.add(left, BorderLayout.WEST);

        // Right Connection Badge
        connection.setOpaque(true);
        connection.setFont(new Font("SansSerif", Font.BOLD, 11));
        connection.setBorder(new EmptyBorder(5, 12, 5, 12));
        header.add(connection, BorderLayout.EAST);

        return header;
    }

    private JPanel createConnectionPanel(Path identityPath) {
        JPanel container = new JPanel(new BorderLayout(0, 6));
        container.setBackground(CARD_BG);
        container.setBorder(new CompoundBorder(
                new MatteBorder(0, 0, 1, 0, BORDER),
                new EmptyBorder(10, 20, 10, 20)
        ));

        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        fields.setOpaque(false);

        // Host
        JLabel hLabel = new JLabel("Host:");
        hLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        hLabel.setForeground(TEXT_PRIMARY);
        styleField(host);
        fields.add(hLabel);
        fields.add(host);

        // Port
        JLabel pLabel = new JLabel("Port:");
        pLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        pLabel.setForeground(TEXT_PRIMARY);
        styleField(port);
        fields.add(pLabel);
        fields.add(port);

        // Name
        JLabel nLabel = new JLabel("Terminal Name:");
        nLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        nLabel.setForeground(TEXT_PRIMARY);
        styleField(name);
        fields.add(nLabel);
        fields.add(name);

        // Connect button
        connect.setFont(new Font("SansSerif", Font.BOLD, 12));
        connect.setBackground(ACCENT);
        connect.setForeground(Color.WHITE);
        connect.setBorder(new EmptyBorder(7, 16, 7, 16));
        connect.setFocusPainted(false);
        connect.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fields.add(connect);

        // Disconnect button
        disconnect.setFont(new Font("SansSerif", Font.BOLD, 12));
        disconnect.setBackground(Color.WHITE);
        disconnect.setForeground(DANGER_COLOR);
        disconnect.setBorder(new CompoundBorder(
                new LineBorder(DANGER_COLOR, 1),
                new EmptyBorder(6, 14, 6, 14)
        ));
        disconnect.setFocusPainted(false);
        disconnect.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        fields.add(disconnect);

        container.add(fields, BorderLayout.NORTH);

        // Identity path subtext
        identity.setFont(new Font("Monospaced", Font.PLAIN, 11));
        identity.setForeground(TEXT_SECONDARY);
        container.add(identity, BorderLayout.SOUTH);

        return container;
    }

    private static void styleField(JTextField field) {
        field.setFont(new Font("SansSerif", Font.PLAIN, 12));
        field.setBorder(new CompoundBorder(
                new LineBorder(BORDER, 1),
                new EmptyBorder(5, 8, 5, 8)
        ));
    }

    private AlertView selected() {
        int row = alerts.getSelectedRow();
        return snapshot != null && row >= 0 && row < snapshot.alerts().size() ? snapshot.alerts().get(row) : null;
    }

    private void render(Snapshot next) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("UI updates require EDT.");
        AlertView before = selected();
        String selectedId = before == null ? null : before.alert().id();
        snapshot = next;
        rendering = true;

        model.setRowCount(0);
        int selectedRow = -1;
        for (int row = 0; row < next.alerts().size(); row++) {
            AlertView entry = next.alerts().get(row);
            var alert = entry.alert();
            model.addRow(new Object[]{
                    alert.title(),
                    alert.priority(),
                    alert.category(),
                    alert.createdAt(),
                    entry.acknowledgement().displayLabel()
            });
            if (alert.id().equals(selectedId)) selectedRow = row;
        }
        if (selectedRow < 0 && !next.alerts().isEmpty()) selectedRow = next.alerts().size() - 1;
        if (selectedRow >= 0) alerts.setRowSelectionInterval(selectedRow, selectedRow);
        rendering = false;

        boolean active = next.state() == ConnectionState.CONNECTED;
        boolean connecting = next.state() == ConnectionState.CONNECTING;

        connect.setEnabled(!active && !connecting);
        connect.setText(next.identity() == null ? "Connect" : "Reconnect");
        disconnect.setEnabled(active || connecting);

        host.setEnabled(!active && !connecting);
        port.setEnabled(!active && !connecting);
        name.setEnabled(!active && !connecting);
        refresh.setEnabled(active && !next.busy());

        // Update connection status badge
        if (active) {
            connection.setText("● CONNECTED");
            connection.setBackground(new Color(6, 78, 59));
            connection.setForeground(new Color(110, 231, 183));
        } else if (connecting) {
            connection.setText("◌ CONNECTING...");
            connection.setBackground(new Color(120, 53, 15));
            connection.setForeground(new Color(253, 230, 138));
        } else {
            connection.setText("○ " + next.state());
            connection.setBackground(new Color(30, 41, 59));
            connection.setForeground(new Color(148, 163, 184));
        }

        if (next.identity() != null) {
            identity.setText("Identity: " + next.identity().clientId() + "  |  Terminal: " + next.identity().clientName());
        }
        notice.setText(next.notice());
        showSelection();
    }

    private void showSelection() {
        AlertView entry = selected();
        acknowledge.setEnabled(entry != null && snapshot.state() == ConnectionState.CONNECTED && !snapshot.busy()
                && entry.acknowledgement() != AckStatusState.ACKNOWLEDGED);

        if (entry == null) {
            details.setText("");
            priority.setText("Select an alert from the table above to view details");
            priority.setBackground(CONTENT_BG);
            priority.setForeground(TEXT_SECONDARY);
            ackStatus.setText(" ");
            return;
        }

        var alert = entry.alert();
        boolean critical = alert.priority() == Priority.CRITICAL;
        boolean medium = alert.priority() == Priority.MEDIUM;

        priority.setText((critical ? "🚨 CRITICAL EMERGENCY — " : (medium ? "⚠️ WARNING — " : "ℹ️ NOTICE — "))
                + alert.priority() + " | " + alert.category());

        if (critical) {
            priority.setBackground(CRITICAL_FG);
            priority.setForeground(Color.WHITE);
        } else if (medium) {
            priority.setBackground(MEDIUM_FG);
            priority.setForeground(Color.WHITE);
        } else {
            priority.setBackground(LOW_FG);
            priority.setForeground(Color.WHITE);
        }

        details.setText(alert.title() + "\n\n"
                + alert.message() + "\n\n"
                + "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                + "Timestamp (UTC): " + alert.createdAt() + "\n"
                + "Server Alert ID: " + alert.id());
        details.setCaretPosition(0);

        // Acknowledged state styling
        AckStatusState ack = entry.acknowledgement();
        ackStatus.setText("Status: " + ack.displayLabel());
        if (ack == AckStatusState.ACKNOWLEDGED) {
            ackStatus.setForeground(SUCCESS_COLOR);
            ackStatus.setText("✅ " + ack.displayLabel());
        } else if (ack == AckStatusState.FAILED) {
            ackStatus.setForeground(DANGER_COLOR);
            ackStatus.setText("❌ " + ack.displayLabel());
        } else if (ack == AckStatusState.PENDING) {
            ackStatus.setForeground(WARNING_COLOR);
            ackStatus.setText("⏳ " + ack.displayLabel());
        } else {
            ackStatus.setForeground(TEXT_SECONDARY);
            ackStatus.setText("○ " + ack.displayLabel());
        }

        acknowledge.setText(ack == AckStatusState.FAILED ? "Retry Acknowledgement" : "Acknowledge Alert");
    }

    @Override
    public void dispose() {
        controller.close();
        super.dispose();
    }
}
