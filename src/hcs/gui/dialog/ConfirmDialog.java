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
 * A yes or no question over the process screen, in the style of the panels. It is modal. The
 * safe answer (the first button) is the default button, and so is Escape: the other takes a click.
 */
@SuppressWarnings("serial")
public final class ConfirmDialog extends JDialog {

    private boolean yes;

    /** Asks whether to go back to the input; true for LEAVE. */
    public static boolean leave(Component parent) {
        return ask(parent, "LEAVE?", "Go back to the input?",
            new String[] {"The tree and the table on screen will be lost.", "Your input stays as you left it."}, "STAY", "LEAVE");
    }

    /** Asks whether to skip to the end of the show, as Instant would; true for YES. */
    public static boolean skip(Component parent) {
        return ask(parent, "SKIP?", "Skip to the end?",
            new String[] {"The result is shown at once, as with Instant.", "Nothing moves while you decide."}, "NO", "YES");
    }

    /** Opens the prompt over {@code parent}, waits for an answer, and says whether it was {@code yesText}. */
    private static boolean ask(Component parent, String title, String question, String[] notes, String noText, String yesText) {
        ConfirmDialog dialog = new ConfirmDialog(SwingUtilities.getWindowAncestor(parent), title, question, notes, noText, yesText);
        dialog.setVisible(true);
        return dialog.yes;
    }

    private ConfirmDialog(Window owner, String title, String question, String[] notes, String noText, String yesText) {
        super(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        setUndecorated(true);   // a title bar would not match the theme

        JLabel lblAsk = new JLabel(question);
        lblAsk.setFont(Theme.FONT_EDITOR_BOLD);
        lblAsk.setForeground(Theme.TEXT_PRIMARY);
        lblAsk.setBorder(new EmptyBorder(0, 0, 8, 0));
        JPanel rows = new JPanel(new GridLayout(0, 1, 0, 4));
        rows.setOpaque(false);
        rows.setBorder(new EmptyBorder(24, 24, 24, 24));
        rows.add(lblAsk);
        for (String note : notes) {
            JLabel lblNote = new JLabel(note);
            lblNote.setFont(Theme.FONT_LABEL);
            lblNote.setForeground(Theme.TEXT_MUTED);
            rows.add(lblNote);
        }

        JButton btnNo = Theme.button(noText);
        btnNo.addActionListener(e -> dispose());
        JButton btnYes = Theme.button(yesText);
        btnYes.addActionListener(e -> {
            yes = true;
            dispose();
        });
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.CENTER, 24, 0));
        actions.setOpaque(false);
        actions.add(btnNo);
        actions.add(btnYes);

        JPanel content = new JPanel(new BorderLayout(0, 20));
        content.setBackground(Theme.CHROME_BG);
        content.setBorder(new EmptyBorder(24, 24, 24, 24));
        content.add(Theme.box(title, rows), BorderLayout.CENTER);
        content.add(actions, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().setDefaultButton(btnNo);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
            JComponent.WHEN_IN_FOCUSED_WINDOW);
        pack();
        setLocationRelativeTo(owner);
    }
}
