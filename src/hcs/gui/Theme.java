package hcs.gui;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.util.Arrays;
import java.util.List;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * One place for the colors and fonts every panel shares, so the app reads as one piece
 * of software. A glowing night-console look: blue and violet on navy (deliberately not
 * green), monospaced throughout, with HUD-style frames and soft light round whatever is active.
 */
public final class Theme {

    private Theme() {
    }

    // -- Palette -------------------------------------------------------
    public static final Color CHROME_BG = new Color(0x08, 0x0C, 0x1C);
    public static final Color EDITOR_BG = new Color(0x0D, 0x13, 0x2E);
    public static final Color NAVY = new Color(0x16, 0x24, 0x56);
    public static final Color DIVIDER = new Color(0x1E, 0x2D, 0x5E);
    public static final Color TEXT_PRIMARY = new Color(0xCF, 0xD9, 0xFF);
    public static final Color TEXT_MUTED = new Color(0x60, 0x75, 0xB0);
    public static final Color ACCENT = new Color(0x6B, 0x9B, 0xFF);
    public static final Color VIOLET = new Color(0xB3, 0x9B, 0xFF);
    public static final Color CARET = new Color(0xFF, 0x9E, 0x64);
    public static final Color ERROR = new Color(0xFF, 0x6E, 0x8A);
    public static final Color SELECTION = new Color(0x2A, 0x3F, 0x8F);
    public static final Color ACCENT_HOVER = new Color(0x9D, 0xBB, 0xFF);
    public static final Color ACCENT_PRESSED = new Color(0x4D, 0x7B, 0xE0);
    private static final Color SCROLL_THUMB = new Color(0x2A, 0x38, 0x66);
    /** Laid over a disabled box: the chrome color, almost opaque. */
    public static final Color VEIL = new Color(0x08, 0x0C, 0x1C, 0xF4);

    static {
        // Tooltips (the cubes show their numbers in one) match the rest instead of Swing's pale yellow.
        UIManager.put("ToolTip.background", CHROME_BG);
        UIManager.put("ToolTip.foreground", TEXT_PRIMARY);
        UIManager.put("ToolTip.border", new CompoundBorder(new LineBorder(mix(DIVIDER, ACCENT, 0.4)), new EmptyBorder(5, 9, 5, 9)));
    }

    /** The color {@code t} of the way (0 to 1) from {@code a} to {@code b}. */
    public static Color mix(Color a, Color b, double t) {
        t = Math.max(0, Math.min(1, t));
        return new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
            (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    /** {@code c} at {@code alpha} (0 to 1) of its opacity. */
    public static Color alpha(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * Math.max(0, Math.min(1, alpha))));
    }

    /**
     * Soft light round the outline of {@code s}: strokes from wide and faint to thin and strong, so the
     * light is brightest at the edge ({@code peak}, 0 to 1) and gone {@code radius} pixels out.
     */
    public static void glow(Graphics2D g, Shape s, Color c, int radius, double peak) {
        Stroke old = g.getStroke();
        for (int i = radius; i >= 1; i--) {
            double f = 1 - (i - 1) / (double) radius;
            g.setColor(alpha(c, peak * 3 * f * f / radius));
            g.setStroke(new BasicStroke(i * 2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(s);
        }
        g.setStroke(old);
    }

    /** The light round a box, painted by whoever holds the box, under it. */
    public static void halo(Graphics g, Component box) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        glow(g2, box.getBounds(), ACCENT, 14, 0.2);
        g2.dispose();
    }

    /** The background of a whole screen: a pool of navy light in the middle, fading into the chrome. */
    public static void backdrop(Graphics g, JComponent c) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setPaint(new RadialGradientPaint(c.getWidth() / 2f, c.getHeight() * 0.45f, Math.max(c.getWidth(), c.getHeight()) * 0.75f,
            new float[] {0f, 1f}, new Color[] {alpha(NAVY, 0.9), alpha(NAVY, 0)}));
        g2.fillRect(0, 0, c.getWidth(), c.getHeight());
        g2.dispose();
    }

    /**
     * The frame of a box, drawn inside the {@code w} by {@code h} at ({@code x}, {@code y}): a glowing accent edge on
     * top, a thin outline with a faint light inside it, and HUD corner brackets that are {@code bracket} long.
     */
    public static void frame(Graphics2D g, int x, int y, int w, int h, int bracket) {
        g.setColor(DIVIDER);
        g.drawRect(x, y, w - 1, h - 1);
        g.setColor(alpha(ACCENT, 0.16));
        g.drawRect(x + 1, y + 1, w - 3, h - 3);
        g.setColor(alpha(ACCENT, 0.07));
        g.drawRect(x + 2, y + 2, w - 5, h - 5);
        g.setPaint(new GradientPaint(x, 0, ACCENT_PRESSED, x + w / 2f, 0, ACCENT_HOVER, true));
        g.fillRect(x, y, w, 3);
        g.setColor(ACCENT_HOVER);
        g.fillRect(x, y, 2, bracket);
        g.fillRect(x + w - 2, y, 2, bracket);
        g.fillRect(x, y + h - 2, bracket, 2);
        g.fillRect(x, y + h - bracket, 2, bracket);
        g.fillRect(x + w - bracket, y + h - 2, bracket, 2);
        g.fillRect(x + w - 2, y + h - bracket, 2, bracket);
    }

    /**
     * A faint grid of dots over {@code c}, lined up with the window rather than with {@code c},
     * so that panels painted separately still make one grid. The grid is moved by
     * ({@code dx}, {@code dy}) pixels, for a background that can be dragged.
     */
    public static void dots(Graphics g, JComponent c, int dx, int dy) {
        Point origin = c.getRootPane() == null ? new Point() : SwingUtilities.convertPoint(c, 0, 0, c.getRootPane());
        g.setColor(DIVIDER);
        for (int x = Math.floorMod(12 - origin.x + dx, 24); x < c.getWidth(); x += 24) {
            for (int y = Math.floorMod(12 - origin.y + dy, 24); y < c.getHeight(); y += 24) {
                g.fillRect(x, y, 2, 2);
            }
        }
    }

    // -- Fonts (first coding font this machine has, else Java's monospace) --
    private static final String MONO = monoFamily();
    public static final Font FONT_EDITOR = new Font(MONO, Font.PLAIN, 16);
    public static final Font FONT_LABEL = new Font(MONO, Font.PLAIN, 13);
    public static final Font FONT_BUTTON = new Font(MONO, Font.BOLD, 13);
    /** For what names a thing (a box, a card, a column) rather than describes it. */
    public static final Font FONT_TITLE = new Font(MONO, Font.BOLD, 13);
    public static final Font FONT_EDITOR_BOLD = new Font(MONO, Font.BOLD, 16);

    private static String monoFamily() {
        List<String> installed = Arrays.asList(GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames());
        for (String family : new String[] {"JetBrains Mono", "Cascadia Mono", "Consolas", "Menlo", "DejaVu Sans Mono"}) {
            if (installed.contains(family)) {
                return family;
            }
        }
        return Font.MONOSPACED;
    }

    /** How far the point of a button reaches out from its flat top and bottom edge. */
    private static final int SPIKE = 40;
    /** The same on a bar: the action at the foot of a box, which has less room to be pointed in. */
    private static final int BAR_SPIKE = 14;
    /** Room kept round a button's shape for its glow. */
    private static final int GLOW = 6;

    /** A filled button in the accent color, pointed at both ends: the main action of a screen. */
    public static JButton button(String text) {
        return button(text, true, SPIKE, 11);
    }

    /** The same button as an outline, for the actions that are not the main one. */
    public static JButton ghost(String text) {
        return button(text, false, SPIKE, 11);
    }

    /** An outline button with short points, to fill the width of the strip at the foot of a box. */
    public static JButton bar(String text) {
        return button(text, false, BAR_SPIKE, 8);
    }

    /** Only the background is custom painted. */
    @SuppressWarnings("serial")
    private static JButton button(String text, boolean filled, int spike, int pad) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth();
                int h = getHeight();
                boolean lit = isEnabled() && (getModel().isRollover() || getModel().isPressed());
                // Inside the edge by the room for the glow, and by a pixel more so the 2 pixel line is not cut off.
                int in = GLOW + (filled ? 0 : 1);
                Polygon shape = new Polygon(new int[] {in, GLOW + spike, w - GLOW - spike, w - in, w - GLOW - spike, GLOW + spike},
                    new int[] {h / 2, in, in, h / 2, h - in, h - in}, 6);
                if (isEnabled()) {
                    glow(g2, shape, ACCENT, GLOW, getModel().isPressed() ? 0.7 : lit ? 0.55 : filled ? 0.4 : 0.2);
                }
                if (filled) {
                    Color base = getModel().isPressed() ? ACCENT_PRESSED : lit ? ACCENT_HOVER : ACCENT;
                    g2.setPaint(new GradientPaint(0, in, mix(base, Color.WHITE, 0.22), 0, h - in, base));
                    g2.fillPolygon(shape);
                } else {
                    if (lit) {
                        g2.setColor(alpha(ACCENT, getModel().isPressed() ? 0.28 : 0.15));
                        g2.fillPolygon(shape);
                    }
                    g2.setStroke(new BasicStroke(2));
                    g2.setColor(!isEnabled() ? TEXT_MUTED : lit ? ACCENT_HOVER : ACCENT);
                    g2.drawPolygon(shape);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        button.setFont(FONT_BUTTON);
        button.setForeground(filled ? CHROME_BG : ACCENT_HOVER);
        button.setBorder(new EmptyBorder(pad + GLOW, spike + 14 + GLOW, pad + GLOW, spike + 14 + GLOW));
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        // A click target only, so the text box keeps the keyboard focus when the window opens.
        button.setFocusable(false);
        button.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return button;
    }

    /** The edge of a box: see {@link #frame}. Wide enough for the light inside the outline, so nothing paints over it. */
    @SuppressWarnings("serial")
    private static final class FrameBorder extends AbstractBorder {

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(3, 3, 3, 3);
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            frame((Graphics2D) g, x, y, w, h, 14);
        }
    }

    /** The small glowing diamond in front of a box's title. */
    private record Chip() implements Icon {

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setPaint(new RadialGradientPaint(x + 7, y + 7, 7, new float[] {0f, 1f}, new Color[] {alpha(ACCENT, 0.6), alpha(ACCENT, 0)}));
            g2.fillRect(x, y, 14, 14);
            g2.setColor(ACCENT_HOVER);
            g2.fillPolygon(new int[] {x + 7, x + 11, x + 7, x + 3}, new int[] {y + 3, y + 7, y + 11, y + 7}, 4);
            g2.dispose();
        }
    }

    /** A titled box with a glowing accent edge on top and HUD brackets in its corners. */
    @SuppressWarnings("serial")
    public static JPanel box(String title, JComponent body) {
        JLabel lblTitle = new JLabel(title, new Chip(), SwingConstants.LEADING);
        lblTitle.setFont(FONT_TITLE);
        lblTitle.setForeground(TEXT_PRIMARY);
        lblTitle.setIconTextGap(8);
        lblTitle.setBorder(new EmptyBorder(11, 13, 11, 16));

        JPanel box = new JPanel(new BorderLayout()) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                // Light spilling down from the top edge, behind the title.
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setPaint(new GradientPaint(0, 3, alpha(ACCENT, 0.2), 0, 40, alpha(ACCENT, 0)));
                g2.fillRect(3, 3, getWidth() - 6, 37);
                g2.dispose();
            }
        };
        box.setBackground(EDITOR_BG);
        box.setBorder(new FrameBorder());
        box.add(lblTitle, BorderLayout.NORTH);
        box.add(body, BorderLayout.CENTER);
        return box;
    }

    /** Wraps a view in a scroll pane in the app's style: dark, a slim scroll bar, never sideways. */
    public static JScrollPane scroll(JComponent view) {
        JScrollPane pane = new JScrollPane(view, JScrollPane.VERTICAL_SCROLLBAR_ALWAYS,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Empty, not null: a JTable in a scroll pane swaps a null border for Metal's bevel.
        pane.setBorder(new EmptyBorder(0, 0, 0, 0));
        pane.getViewport().setBackground(EDITOR_BG);
        pane.getVerticalScrollBar().setUI(new SlimScrollBarUI());
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(14, 0));
        return pane;
    }

    /** A square thumb on a track that blends into the editor; no arrow buttons. */
    private static final class SlimScrollBarUI extends BasicScrollBarUI {

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return noButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return noButton();
        }

        private static JButton noButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
            g.setColor(EDITOR_BG);
            g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
            g.setColor(isDragging || isThumbRollover() ? TEXT_MUTED : SCROLL_THUMB);
            g.fillRect(bounds.x + 3, bounds.y + 2, bounds.width - 6, bounds.height - 4);
        }
    }
}
