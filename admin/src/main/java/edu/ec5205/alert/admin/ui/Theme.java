package edu.ec5205.alert.admin.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.LayoutManager;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

/**
 * Shared design-system constants and factory methods for the
 * Emergency Alert & Broadcast System UI.
 *
 * <p>Pure utility class – do not instantiate.</p>
 */
public final class Theme {

    private Theme() {
        throw new AssertionError("Theme is a utility class");
    }

    // -------------------------------------------------------------------------
    // Colors
    // -------------------------------------------------------------------------

    public static final Color NAV_BG          = new Color(15,  23,  42);
    public static final Color NAV_ACTIVE      = new Color(30,  64,  175);
    public static final Color NAV_HOVER       = new Color(30,  41,  59);
    public static final Color ACCENT          = new Color(37,  99,  235);
    public static final Color CONTENT_BG      = new Color(248, 250, 252);
    public static final Color CARD_BG         = Color.WHITE;
    public static final Color BORDER          = new Color(226, 232, 240);
    public static final Color TEXT_PRIMARY    = new Color(15,  23,  42);
    public static final Color TEXT_SECONDARY  = new Color(100, 116, 139);
    public static final Color TEXT_ON_DARK    = Color.WHITE;
    public static final Color SUCCESS         = new Color(16,  185, 129);
    public static final Color WARNING         = new Color(245, 158, 11);
    public static final Color DANGER          = new Color(239, 68,  68);
    public static final Color CRITICAL_BG     = new Color(254, 226, 226);
    public static final Color CRITICAL_FG     = new Color(185, 28,  28);
    public static final Color MEDIUM_BG       = new Color(255, 237, 213);
    public static final Color MEDIUM_FG       = new Color(180, 83,  9);
    public static final Color LOW_BG          = new Color(220, 252, 231);
    public static final Color LOW_FG          = new Color(21,  128, 61);
    public static final Color DISCONNECTED    = new Color(100, 116, 139);

    // -------------------------------------------------------------------------
    // Fonts
    // -------------------------------------------------------------------------

    public static final Font FONT_TITLE  = new Font("SansSerif",   Font.BOLD,  22);
    public static final Font FONT_HEADER = new Font("SansSerif",   Font.BOLD,  15);
    public static final Font FONT_BODY   = new Font("SansSerif",   Font.PLAIN, 13);
    public static final Font FONT_LABEL  = new Font("SansSerif",   Font.BOLD,  12);
    public static final Font FONT_SMALL  = new Font("SansSerif",   Font.PLAIN, 11);
    public static final Font FONT_MONO   = new Font("Monospaced",  Font.PLAIN, 11);

    // -------------------------------------------------------------------------
    // Button factories
    // -------------------------------------------------------------------------

    /** Primary action button – ACCENT background, white text. */
    public static JButton primaryButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(ACCENT);
        btn.setForeground(TEXT_ON_DARK);
        btn.setFont(FONT_LABEL);
        btn.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        return btn;
    }

    /** Danger / destructive action button – DANGER background, white text. */
    public static JButton dangerButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(DANGER);
        btn.setForeground(TEXT_ON_DARK);
        btn.setFont(FONT_LABEL);
        btn.setBorder(BorderFactory.createEmptyBorder(8, 20, 8, 20));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        return btn;
    }

    /** Secondary / outlined button – white background, BORDER border, TEXT_PRIMARY text. */
    public static JButton secondaryButton(String text) {
        JButton btn = new JButton(text);
        btn.setBackground(CARD_BG);
        btn.setForeground(TEXT_PRIMARY);
        btn.setFont(FONT_LABEL);
        btn.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(7, 19, 7, 19)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setOpaque(true);
        btn.setContentAreaFilled(true);
        return btn;
    }

    // -------------------------------------------------------------------------
    // Label factories
    // -------------------------------------------------------------------------

    /** Section / panel title label. */
    public static JLabel sectionTitle(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(FONT_HEADER);
        lbl.setForeground(TEXT_PRIMARY);
        return lbl;
    }

    /** Standard body text label. */
    public static JLabel bodyLabel(String text) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(FONT_BODY);
        lbl.setForeground(TEXT_SECONDARY);
        return lbl;
    }

    /**
     * Coloured status badge label.
     *
     * @param text badge text
     * @param bg   background colour
     * @param fg   foreground (text) colour
     */
    public static JLabel statusBadge(String text, Color bg, Color fg) {
        JLabel lbl = new JLabel(text);
        lbl.setFont(FONT_LABEL);
        lbl.setForeground(fg);
        lbl.setBackground(bg);
        lbl.setOpaque(true);
        lbl.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        return lbl;
    }

    // -------------------------------------------------------------------------
    // Input factories
    // -------------------------------------------------------------------------

    /** Styled single-line text field. */
    public static JTextField styledField(int cols) {
        JTextField field = new JTextField(cols);
        field.setFont(FONT_BODY);
        field.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        return field;
    }

    /** Styled password field (same visual style as styledField). */
    public static JPasswordField styledPasswordField(int cols) {
        JPasswordField field = new JPasswordField(cols);
        field.setFont(FONT_BODY);
        field.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        return field;
    }

    /** Styled multi-line text area with line-wrap enabled. */
    public static JTextArea styledTextArea(int rows, int cols) {
        JTextArea area = new JTextArea(rows, cols);
        area.setFont(FONT_BODY);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        return area;
    }

    /** Styled combo-box. */
    @SuppressWarnings("unchecked")
    public static JComboBox<?> styledCombo(Object[] items) {
        JComboBox<?> combo = new JComboBox<>(items);
        combo.setFont(FONT_BODY);
        combo.setBorder(BorderFactory.createLineBorder(BORDER, 1));
        return combo;
    }

    // -------------------------------------------------------------------------
    // Container / scroll factories
    // -------------------------------------------------------------------------

    /** Borderless scroll-pane with CARD_BG viewport background. */
    public static JScrollPane modernScrollPane(Component c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(CARD_BG);
        return sp;
    }

    /**
     * Card panel: white background, 1 px BORDER, 16 px internal padding.
     *
     * @param layout layout manager to apply (may be {@code null})
     */
    public static JPanel card(LayoutManager layout) {
        JPanel panel = layout != null ? new JPanel(layout) : new JPanel();
        panel.setBackground(CARD_BG);
        panel.setBorder(new CompoundBorder(
                BorderFactory.createLineBorder(BORDER, 1),
                BorderFactory.createEmptyBorder(16, 16, 16, 16)));
        return panel;
    }

    /**
     * Content-area panel with CONTENT_BG background.
     *
     * @param layout layout manager to apply (may be {@code null})
     */
    public static JPanel contentPanel(LayoutManager layout) {
        JPanel panel = layout != null ? new JPanel(layout) : new JPanel();
        panel.setBackground(CONTENT_BG);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Table styling
    // -------------------------------------------------------------------------

    /**
     * Applies the shared table style to {@code table}.
     *
     * <ul>
     *   <li>Row height: 32 px</li>
     *   <li>Grid colour: BORDER</li>
     *   <li>Header: NAV_BG background, white FONT_LABEL text</li>
     *   <li>Alternating rows: white / CONTENT_BG</li>
     *   <li>Selection: ACCENT background, white foreground</li>
     *   <li>Horizontal lines shown; vertical lines hidden</li>
     *   <li>Fills viewport height</li>
     * </ul>
     */
    public static void styleTable(JTable table) {
        table.setRowHeight(32);
        table.setGridColor(BORDER);
        table.setShowHorizontalLines(true);
        table.setShowVerticalLines(false);
        table.setIntercellSpacing(new java.awt.Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionBackground(ACCENT);
        table.setSelectionForeground(TEXT_ON_DARK);
        table.setFont(FONT_BODY);

        // Header styling
        JTableHeader header = table.getTableHeader();
        header.setBackground(NAV_BG);
        header.setForeground(TEXT_ON_DARK);
        header.setFont(FONT_LABEL);
        header.setReorderingAllowed(false);

        // Alternating row renderer
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(
                    JTable tbl, Object value, boolean isSelected,
                    boolean hasFocus, int row, int col) {
                Component c = super.getTableCellRendererComponent(
                        tbl, value, isSelected, hasFocus, row, col);
                if (!isSelected) {
                    c.setBackground(row % 2 == 0 ? Color.WHITE : CONTENT_BG);
                    c.setForeground(TEXT_PRIMARY);
                }
                return c;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Priority colour helpers
    // -------------------------------------------------------------------------

    /**
     * Returns the primary accent colour for the given priority level.
     *
     * @param p alert priority
     * @return DANGER, WARNING, or SUCCESS
     */
    public static Color priorityColor(edu.ec5205.alert.common.Priority p) {
        if (p == null) return DISCONNECTED;
        return switch (p) {
            case CRITICAL -> DANGER;
            case MEDIUM   -> WARNING;
            case LOW      -> SUCCESS;
        };
    }

    /**
     * Returns the background badge colour for the given priority level.
     *
     * @param p alert priority
     * @return CRITICAL_BG, MEDIUM_BG, or LOW_BG
     */
    public static Color priorityBgColor(edu.ec5205.alert.common.Priority p) {
        if (p == null) return CONTENT_BG;
        return switch (p) {
            case CRITICAL -> CRITICAL_BG;
            case MEDIUM   -> MEDIUM_BG;
            case LOW      -> LOW_BG;
        };
    }

    /**
     * Returns the foreground badge colour for the given priority level.
     *
     * @param p alert priority
     * @return CRITICAL_FG, MEDIUM_FG, or LOW_FG
     */
    public static Color priorityFgColor(edu.ec5205.alert.common.Priority p) {
        if (p == null) return TEXT_SECONDARY;
        return switch (p) {
            case CRITICAL -> CRITICAL_FG;
            case MEDIUM   -> MEDIUM_FG;
            case LOW      -> LOW_FG;
        };
    }

    // -------------------------------------------------------------------------
    // Global UIManager defaults
    // -------------------------------------------------------------------------

    /**
     * Sets global Swing defaults.  Call once from {@code AdminMain} and
     * {@code ClientMain} <em>before</em> any window is constructed.
     */
    public static void applyGlobalDefaults() {
        UIManager.put("Table.selectionBackground",  ACCENT);
        UIManager.put("Table.selectionForeground",  TEXT_ON_DARK);
        UIManager.put("Button.focus",               new java.awt.Color(0, 0, 0, 0));
        UIManager.put("Panel.background",           CONTENT_BG);
    }
}
