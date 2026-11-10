package edu.ec5205.alert.admin.ui;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;

/**
 * Panel representing real-time alert acknowledgement monitoring.
 */
public final class AlertMonitoringPanel extends JPanel {

    public AlertMonitoringPanel() {
        initUI();
    }

    private void initUI() {
        setLayout(new BorderLayout(15, 15));
        setBorder(new EmptyBorder(25, 25, 25, 25));

        JPanel card = new JPanel(new GridBagLayout());
        card.setBackground(Color.WHITE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 220, 220)),
                new EmptyBorder(20, 20, 20, 20)
        ));

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0; gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(8, 8, 8, 8);

        JLabel titleLabel = new JLabel("Real-Time Broadcast & Acknowledgement Monitoring");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 16));
        titleLabel.setForeground(new Color(50, 50, 50));
        card.add(titleLabel, gbc);

        gbc.gridy = 1;
        JLabel descLabel = new JLabel("<html><center style='width: 500px;'>"
                + "When client receivers acknowledge incoming alerts, the server streams live <code>ACK_STATUS</code> events "
                + "back to authenticated admins over the active TCP socket.<br/><br/>"
                + "<b>Current Server Status:</b> In Milestone 2, alert creation, validation, storage, and broadcasting "
                + "are active. Full live acknowledgement aggregation is scheduled for Milestone 3/4."
                + "</center></html>");
        descLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        descLabel.setForeground(new Color(90, 90, 90));
        card.add(descLabel, gbc);

        gbc.gridy = 2;
        JLabel badge = new JLabel(" Milestone 2 Active: Alert Creation & Storage Ready ");
        badge.setOpaque(true);
        badge.setBackground(new Color(230, 245, 235));
        badge.setForeground(new Color(30, 130, 60));
        badge.setFont(new Font("SansSerif", Font.BOLD, 12));
        badge.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(180, 220, 190)),
                new EmptyBorder(6, 12, 6, 12)
        ));
        card.add(badge, gbc);

        add(card, BorderLayout.CENTER);
    }
}

