package edu.ec5205.alert.client.ui;

import edu.ec5205.alert.client.model.*;
import edu.ec5205.alert.client.service.ReceiverController;
import edu.ec5205.alert.client.service.ReceiverController.*;
import edu.ec5205.alert.common.Priority;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.Path;

/** Desktop receiver. All control actions delegate network work to ReceiverController. */
public final class ReceiverFrame extends JFrame {
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
        super("Emergency Alert Receiver - " + displayName);
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Create receiver on EDT.");
        host = new JTextField(serverHost, 14); port = new JTextField(Integer.toString(serverPort), 5);
        name = new JTextField(displayName, 14);
        controller = new ReceiverController(identityPath, this::render);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(900, 660)); setSize(1060, 740);
        setLocationByPlatform(true);
        JPanel root = new JPanel(new BorderLayout(10, 10)); root.setBorder(new EmptyBorder(14, 16, 14, 16));
        JPanel top = new JPanel(new BorderLayout(5, 5));
        JPanel fields = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        fields.add(new JLabel("Server host")); fields.add(host);
        fields.add(new JLabel("Port")); fields.add(port);
        fields.add(new JLabel("Name")); fields.add(name); fields.add(connect); fields.add(disconnect);
        top.add(fields, BorderLayout.NORTH);
        JPanel info = new JPanel(new GridLayout(3, 1, 2, 2));
        info.add(connection); info.add(identity); info.add(new JLabel("Identity file: " + identityPath.toAbsolutePath()));
        top.add(info, BorderLayout.CENTER); root.add(top, BorderLayout.NORTH);
        alerts.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); alerts.setRowHeight(27);
        alerts.setFillsViewportHeight(true);
        alerts.getColumnModel().getColumn(0).setPreferredWidth(240);
        alerts.getColumnModel().getColumn(3).setPreferredWidth(230);
        alerts.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                                      boolean focus, int row, int column) {
                Component cell = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                boolean critical = snapshot != null && snapshot.alerts().get(row).alert().priority() == Priority.CRITICAL;
                if (!selected) {
                    cell.setBackground(critical ? new Color(255, 226, 226) : Color.WHITE);
                    cell.setForeground(critical ? new Color(145, 15, 25) : Color.DARK_GRAY);
                }
                cell.setFont(cell.getFont().deriveFont(critical ? Font.BOLD : Font.PLAIN));
                return cell;
            }
        });
        alerts.getSelectionModel().addListSelectionListener(event -> { if (!rendering) showSelection(); });
        details.setEditable(false); details.setLineWrap(true); details.setWrapStyleWord(true);
        details.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14)); details.setMargin(new Insets(10, 10, 10, 10));
        JPanel alertDetails = new JPanel(new BorderLayout(6, 6));
        priority.setOpaque(true); priority.setBorder(new EmptyBorder(6, 8, 6, 8));
        alertDetails.add(priority, BorderLayout.NORTH); alertDetails.add(new JScrollPane(details), BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        actions.add(ackStatus); actions.add(refresh); actions.add(acknowledge);
        alertDetails.add(actions, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JScrollPane(alerts), alertDetails);
        split.setResizeWeight(.45); root.add(split, BorderLayout.CENTER);
        notice.setEditable(false); notice.setFocusable(false); notice.setLineWrap(true); notice.setWrapStyleWord(true);
        notice.setBackground(root.getBackground()); notice.setBorder(new EmptyBorder(6, 0, 0, 0));
        root.add(notice, BorderLayout.SOUTH); setContentPane(root);
        connect.addActionListener(event -> {
            try { controller.connect(host.getText(), Integer.parseInt(port.getText().trim()), name.getText()); }
            catch (NumberFormatException e) { notice.setText("Port must be an integer from 1 to 65535."); }
        });
        disconnect.addActionListener(event -> controller.disconnect());
        refresh.addActionListener(event -> controller.requestHistory());
        acknowledge.addActionListener(event -> {
            AlertView selected = selected(); if (selected != null) controller.acknowledge(selected.alert().id());
        });
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { dispose(); } });
        render(controller.snapshot());
    }
    private AlertView selected() {
        int row = alerts.getSelectedRow();
        return snapshot != null && row >= 0 && row < snapshot.alerts().size() ? snapshot.alerts().get(row) : null;
    }
    private void render(Snapshot next) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("UI updates require EDT.");
        AlertView before = selected(); String selectedId = before == null ? null : before.alert().id();
        snapshot = next; rendering = true;
        model.setRowCount(0); int selectedRow = -1;
        for (int row = 0; row < next.alerts().size(); row++) {
            AlertView entry = next.alerts().get(row); var alert = entry.alert();
            model.addRow(new Object[]{alert.title(), alert.priority(), alert.category(), alert.createdAt(), entry.acknowledgement().displayLabel()});
            if (alert.id().equals(selectedId)) selectedRow = row;
        }
        if (selectedRow < 0 && !next.alerts().isEmpty()) selectedRow = next.alerts().size() - 1;
        if (selectedRow >= 0) alerts.setRowSelectionInterval(selectedRow, selectedRow);
        rendering = false;
        boolean active = next.state() == ConnectionState.CONNECTED;
        boolean connecting = next.state() == ConnectionState.CONNECTING;
        connect.setEnabled(!active && !connecting); connect.setText(next.identity() == null ? "Connect" : "Reconnect");
        disconnect.setEnabled(active || connecting);
        host.setEnabled(!active && !connecting); port.setEnabled(!active && !connecting); name.setEnabled(!active && !connecting);
        refresh.setEnabled(active && !next.busy());
        connection.setText("Status: " + next.state());
        connection.setForeground(active ? new Color(20, 110, 40) : new Color(155, 40, 30));
        if (next.identity() != null) identity.setText("Client ID: " + next.identity().clientId() + " | " + next.identity().clientName());
        notice.setText(next.notice()); showSelection();
    }
    private void showSelection() {
        AlertView entry = selected();
        acknowledge.setEnabled(entry != null && snapshot.state() == ConnectionState.CONNECTED && !snapshot.busy()
                && entry.acknowledgement() != AckStatusState.ACKNOWLEDGED);
        if (entry == null) { details.setText(""); priority.setText("Select an alert"); ackStatus.setText(" "); return; }
        var alert = entry.alert(); boolean critical = alert.priority() == Priority.CRITICAL;
        priority.setText((critical ? "CRITICAL ALERT - " : "") + alert.priority() + " | " + alert.category());
        priority.setBackground(critical ? new Color(160, 20, 35) : new Color(225, 235, 245));
        priority.setForeground(critical ? Color.WHITE : Color.DARK_GRAY);
        details.setText(alert.title() + "\n\n" + alert.message() + "\n\nCreated (UTC): " + alert.createdAt() + "\nAlert ID: " + alert.id());
        details.setCaretPosition(0);
        ackStatus.setText(entry.acknowledgement().displayLabel());
        acknowledge.setText(entry.acknowledgement() == AckStatusState.FAILED ? "Retry Acknowledgement" : "Acknowledge");
    }
    @Override public void dispose() { controller.close(); super.dispose(); }
}
