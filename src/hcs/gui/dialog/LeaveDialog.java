package hcs.gui.dialog;

import hcs.gui.Theme;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Window;
import java.awt.event.KeyEvent;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

/**
 * Asks whether to leave the process screen, in the style of the panels. It is modal. STAY is the
 * default button, and so is Escape: leaving takes a click.
 */
@SuppressWarnings("serial")
public final class LeaveDialog extends JDialog {

    private boolean leave;

    /** Opens the prompt over {@code parent}, waits for an answer, and says whether it was LEAVE. */
    public static boolean ask(Component parent) {
        LeaveDialog dialog = new LeaveDialog(SwingUtilities.getWindowAncestor(parent));
        dialog.setVisible(true);
        return dialog.leave;
    }

    private LeaveDialog(Window owner) {
        super(owner, "Leave", Dialog.ModalityType.APPLICATION_MODAL);
        setUndecorated(true);   // a title bar would not match the theme

        JLabel lblAsk = new JLabel("Go back to the input?");
        lblAsk.setFont(Theme.FONT_EDITOR_BOLD);
        lblAsk.setForeground(Theme.TEXT_PRIMARY);
        lblAsk.setBorder(new EmptyBorder(0, 0, 8, 0));
        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 4));
        rows.setOpaque(false);
        rows.setBorder(new EmptyBorder(24, 24, 24, 24));
        rows.add(lblAsk);
        for (String note : new String[] {"The tree and the table on screen will be lost.", "Your input stays as you left it."}) {
            JLabel lblNote = new JLabel(note);
            lblNote.setFont(Theme.FONT_LABEL);
            lblNote.setForeground(Theme.TEXT_MUTED);
            rows.add(lblNote);
        }

        JButton btnStay = Theme.button("STAY");
        btnStay.addActionListener(e -> dispose());
        JButton btnLeave = Theme.button("LEAVE");
        btnLeave.addActionListener(e -> {
            leave = true;
            dispose();
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 24, 0));
        actions.setOpaque(false);
        actions.add(btnStay);
        actions.add(btnLeave);

        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setBackground(Theme.CHROME_BG);
        content.setBorder(new EmptyBorder(24, 24, 24, 24));
        content.add(Theme.box("LEAVE?", rows), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(btnStay);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }
}
