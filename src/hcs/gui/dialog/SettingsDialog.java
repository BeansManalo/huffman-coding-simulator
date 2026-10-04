package hcs.gui.dialog;

import hcs.gui.Theme;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * The settings window, in the style of the panels. It is modal, so the window behind it
 * cannot be used until DONE (or Escape). A choice counts the moment it is clicked.
 */
@SuppressWarnings("serial")
public final class SettingsDialog extends JDialog {

    /** How the tree is shown once it is built. */
    public enum DisplayMode {
        ANIMATED("Animated", "Watch the tree being built step by step."),
        INSTANT("Instant", "Skip the animation and show the result at once.");

        private final String title;
        private final String note;

        DisplayMode(String title, String note) {
            this.title = title;
            this.note = note;
        }
    }

    private DisplayMode selected;

    /** Opens the window over {@code parent}, waits for DONE, and returns the mode that is chosen then. */
    public static DisplayMode show(Component parent, DisplayMode current) {
        SettingsDialog dialog = new SettingsDialog(SwingUtilities.getWindowAncestor(parent), current);
        dialog.setVisible(true);
        return dialog.selected;
    }

    private SettingsDialog(Window owner, DisplayMode current) {
        super(owner, "Settings", Dialog.ModalityType.APPLICATION_MODAL);
        selected = current;
        setUndecorated(true);   // a title bar would not match the theme

        ButtonGroup group = new ButtonGroup();
        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 22));
        rows.setOpaque(false);
        rows.setBorder(new EmptyBorder(24, 24, 24, 24));
        for (DisplayMode mode : DisplayMode.values()) {
            JRadioButton radio = new JRadioButton(mode.title, new Diamond(false));
            radio.setSelectedIcon(new Diamond(true));
            radio.setSelected(mode == current);
            radio.setFont(Theme.FONT_EDITOR_BOLD);
            radio.setForeground(Theme.TEXT_PRIMARY);
            radio.setIconTextGap(14);
            radio.setOpaque(false);
            radio.setContentAreaFilled(false);
            radio.setBorder(null);
            radio.setFocusPainted(false);
            radio.addActionListener(e -> selected = mode);
            group.add(radio);

            JLabel lblNote = new JLabel(mode.note);
            lblNote.setFont(Theme.FONT_LABEL);
            lblNote.setForeground(Theme.TEXT_MUTED);
            lblNote.setBorder(new EmptyBorder(0, 16 + radio.getIconTextGap(), 0, 0));   // lines up under the title
            lblNote.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    radio.doClick();
                }
            });

            JPanel row = new JPanel(new BorderLayout(0, 4));
            row.setOpaque(false);
            row.add(radio, BorderLayout.NORTH);
            row.add(lblNote, BorderLayout.CENTER);
            rows.add(row);
        }

        JButton btnDone = Theme.button("DONE");
        btnDone.addActionListener(e -> dispose());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 24, 0));
        actions.setOpaque(false);
        actions.add(btnDone);

        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setBackground(Theme.CHROME_BG);
        content.setBorder(new EmptyBorder(24, 24, 24, 24));
        content.add(Theme.box("DISPLAY MODE", rows), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(btnDone);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }

    /** The marker beside an option: a solid diamond when it is chosen, an outline when it is not. */
    private record Diamond(boolean filled) implements Icon {

        private static final int SIZE = 16;

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int inset = filled ? 0 : 1;   // the outline's stroke is drawn centered on the edge
            int mid = SIZE / 2;
            Polygon shape = new Polygon(new int[] {x + mid, x + SIZE - inset, x + mid, x + inset},
                new int[] {y + inset, y + mid, y + SIZE - inset, y + mid}, 4);
            if (filled) {
                g2.setColor(Theme.ACCENT);
                g2.fillPolygon(shape);
            } else {
                g2.setColor(Theme.TEXT_MUTED);
                g2.setStroke(new BasicStroke(2));
                g2.drawPolygon(shape);
            }
            g2.dispose();
        }
    }
}
