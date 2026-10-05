package hcs.gui;

import hcs.core.FrequencyScan;
import hcs.gui.dialog.SettingsDialog.DisplayMode;
import hcs.gui.input.InputPanel;
import hcs.gui.process.ProcessPanel;
import hcs.gui.transition.CrtTransition;
import java.awt.Dimension;
import javax.swing.JFrame;
import javax.swing.JPanel;

@SuppressWarnings("serial")
public class MainFrame extends JFrame {

    /**
     * The window's usable area. 16:9 like the Library Management System's screens, but
     * bigger than its 640x360 base, and fixed: nothing is scaled or resized, so every
     * panel is laid out directly at this size.
     */
    public static final Dimension WINDOW_SIZE = new Dimension(1152, 648);

    private final InputPanel input = new InputPanel(this::process);

    /**
     * Create the frame.
     */
    public MainFrame() {
        setTitle("Huffman Coding Simulator");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        // Before pack(), so the window is sized for its final (fixed) borders.
        setResizable(false);
        setContentPane(input);
        pack();
        setLocationRelativeTo(null);
    }

    /** Submit: shows the process screen. Animated, the old-TV effect leads into it. */
    private void process(FrequencyScan scan, DisplayMode mode) {
        boolean animated = mode == DisplayMode.ANIMATED;
        Runnable back = () -> show(input, animated, null);
        // Made now, so the input is already being read while the TV switches off.
        ProcessPanel screen = new ProcessPanel(scan, animated, back, () -> skip(scan, back));
        show(screen, animated, screen::start);
    }

    /**
     * SKIP was confirmed: static covers the show, and under it the process screen is replaced by the
     * Instant one, made from the input that is already read. It is made now, so it is ready by then.
     */
    private void skip(FrequencyScan scan, Runnable back) {
        ProcessPanel finished = new ProcessPanel(scan, false, back, null);
        CrtTransition.snow(this, () -> {
            setContentPane(finished);
            validate();
            finished.start();   // Instant: everything is shown at once
        }, null);
    }

    /**
     * Puts {@code screen} in the window, with the old-TV effect when {@code tv}: switching off and on,
     * or, back to the input, losing the signal. {@code done} runs once it is showing.
     */
    void show(JPanel screen, boolean tv, Runnable done) {
        Runnable swap = () -> {
            setContentPane(screen);
            validate();
        };
        if (tv) {
            if (screen == input) {
                CrtTransition.glitch(this, swap, done);
            } else {
                CrtTransition.play(this, swap, done);
            }
            return;
        }
        swap.run();
        repaint();
        if (done != null) {
            done.run();
        }
    }
}
