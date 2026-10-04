package hcs.gui.transition;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.TexturePaint;
import java.awt.event.KeyAdapter;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.Timer;

/**
 * Changes the screen the way an old TV does: the picture squashes into a bright line, the
 * line shrinks into a dot, the dot fades out. Then, in the dark, the next screen is put in
 * place and the whole thing runs backwards. It plays on the frame's glass pane, over a
 * snapshot of each screen, so the screens themselves know nothing about it.
 * <p>
 * {@link #glitch} is the other way a TV changes screen: it loses its signal. The picture tears
 * sideways, splits into red and blue, rolls and drowns in static; a burst of snow covers the
 * change; then the new screen locks in from the top, a bright line sweeping down it.
 */
@SuppressWarnings("serial")
public final class CrtTransition extends JComponent {

    // Milliseconds. Switching off, a black pause, then switching on.
    private static final int SQUASH = 300;
    private static final int SHRINK = 220;
    private static final int FADE = 260;
    private static final int OFF_END = SQUASH + SHRINK + FADE;
    private static final int BLACK = 240;
    private static final int SPARK = 180;
    private static final int GROW = 240;
    private static final int OPEN = 440;
    private static final int ON_START = OFF_END + BLACK;
    private static final int TOTAL = ON_START + SPARK + GROW + OPEN;
    // Milliseconds. Losing the signal, a burst of static, locking in.
    private static final int UNLOCK = 1000;
    private static final int STATIC = 280;
    private static final int LOCK = 820;
    /** How thick the line is, in pixels. */
    private static final int LINE = 3;
    private static final Color GLOW = new Color(0x9D, 0xB8, 0xFA);

    private final JFrame frame;
    private final Component oldGlass;
    private final Runnable swap;
    private final Runnable done;
    private final BufferedImage before;
    private BufferedImage after;
    private final TexturePaint scanlines;
    private final long start = System.nanoTime();
    private final boolean glitch;
    private final int offEnd;   // when the old screen is gone, and the new one may be put in place
    private final int total;
    private final Random rnd = new Random();
    private final BufferedImage snow;
    private final BufferedImage warped;
    private final int[] tear;       // how far each row is torn sideways
    private double tornAt = -1000;  // when the tears were last changed
    private double t;

    /**
     * Plays the effect over {@code frame}. {@code swap} puts the next screen in place while the
     * picture is dark; {@code done}, if there is one, runs when the next screen is showing for real.
     */
    public static void play(JFrame frame, Runnable swap, Runnable done) {
        new CrtTransition(frame, swap, done, false);
    }

    /** Like {@link #play}, but the TV loses its signal instead of switching off. */
    public static void glitch(JFrame frame, Runnable swap, Runnable done) {
        new CrtTransition(frame, swap, done, true);
    }

    private CrtTransition(JFrame frame, Runnable swap, Runnable done, boolean glitch) {
        this.frame = frame;
        this.swap = swap;
        this.done = done;
        this.glitch = glitch;
        offEnd = glitch ? UNLOCK : OFF_END;
        total = glitch ? UNLOCK + STATIC + LOCK : TOTAL;
        before = snapshot(frame.getContentPane());
        warped = glitch ? new BufferedImage(before.getWidth(), before.getHeight(), BufferedImage.TYPE_INT_RGB) : null;
        tear = new int[before.getHeight()];
        snow = glitch ? snow() : null;
        BufferedImage lines = new BufferedImage(1, 3, BufferedImage.TYPE_INT_ARGB);
        lines.setRGB(0, 2, 0x50000000);
        scanlines = new TexturePaint(lines, new Rectangle(0, 0, 1, 3));

        // Swallow input, so nothing can be clicked or typed into the screen underneath.
        addMouseListener(new MouseAdapter() {
        });
        addMouseMotionListener(new MouseMotionAdapter() {
        });
        addKeyListener(new KeyAdapter() {
        });
        setFocusable(true);
        setOpaque(true);

        oldGlass = frame.getGlassPane();
        frame.setGlassPane(this);
        setVisible(true);
        requestFocusInWindow();
        new Timer(15, e -> step((Timer) e.getSource())).start();
    }

    private void step(Timer timer) {
        t = (System.nanoTime() - start) / 1e6;
        if (after == null && t >= offEnd + 30) {
            swap.run();
            after = snapshot(frame.getContentPane());
        }
        if (t >= total && after != null) {
            timer.stop();
            frame.setGlassPane(oldGlass);
            oldGlass.setVisible(false);   // setGlassPane passes this one's "visible" on; shown, it would stop the next play from being laid out and painted
            if (done != null) {
                done.run();
            }
            return;
        }
        repaint();
    }

    private static BufferedImage snapshot(Container c) {
        BufferedImage image = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        c.paint(g);
        g.dispose();
        return image;
    }

    // -- Painting ------------------------------------------------------
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        int w = getWidth();
        int h = getHeight();
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, w, h);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        double cx = w / 2.0;
        double cy = h / 2.0;
        if (glitch) {
            signalLoss(g2, w, h);
            g2.dispose();
            return;
        }
        double flat = (double) LINE / h;   // the picture's height, as a share of full, once it is a line

        if (t < SQUASH) {
            double p = easeIn(t / SQUASH);
            picture(g2, before, w, h, 1 - (1 - flat) * p, 0.8 * p * p, p);
            glow(g2, cx, cy, cx, p * p);
        } else if (t < SQUASH + SHRINK) {
            glow(g2, cx, cy, cx * (1 - easeIn((t - SQUASH) / SHRINK)), 1);
        } else if (t < OFF_END) {
            double p = (t - SQUASH - SHRINK) / FADE;
            dot(g2, cx, cy, 20 - 14 * p, 1 - p);
        } else if (t < ON_START) {
            // dark: the next screen is put in place now
        } else if (t < ON_START + SPARK) {
            double p = (t - ON_START) / SPARK;
            dot(g2, cx, cy, 3 + 17 * easeOut(p), p);
        } else if (t < ON_START + SPARK + GROW) {
            double p = easeOut((t - ON_START - SPARK) / GROW);
            glow(g2, cx, cy, 6 + (cx - 6) * p, 1);
        } else if (after != null) {
            double p = Math.min(1, (t - ON_START - SPARK - GROW) / OPEN);
            double e = easeOut(p);
            double flicker = 0.85 + 0.15 * Math.sin(t * 0.12);
            picture(g2, after, w, h, flat + (1 - flat) * e, 0.85 * (1 - p) * (1 - p) * flicker, 1 - p);
            glow(g2, cx, cy, cx, (1 - e) * (1 - e));
        }
        g2.dispose();
    }

    /** The snapshot, squashed to {@code sy} of its height around the middle, whitened and striped. */
    private void picture(Graphics2D g2, BufferedImage image, int w, int h, double sy, double white, double stripes) {
        int ih = Math.max(1, (int) Math.round(h * sy));
        int y = (h - ih) / 2;
        g2.drawImage(image, 0, y, w, ih, null);
        if (white > 0) {
            g2.setColor(new Color(1f, 1f, 1f, (float) Math.min(1, white)));
            g2.fillRect(0, y, w, ih);
        }
        if (stripes > 0) {
            g2.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1, stripes)));
            g2.setPaint(scanlines);
            g2.fillRect(0, y, w, ih);
            g2.setComposite(AlphaComposite.SrcOver);
        }
    }

    /** The bright line, {@code halfWidth} either side of the middle, with a soft halo. */
    private static void glow(Graphics2D g2, double cx, double cy, double halfWidth, double strength) {
        if (halfWidth < 1 || strength <= 0) {
            return;
        }
        int x = (int) Math.round(cx - halfWidth);
        int width = (int) Math.round(2 * halfWidth);
        int halo = 44;
        g2.setPaint(new LinearGradientPaint(0, (float) (cy - halo / 2.0), 0, (float) (cy + halo / 2.0),
            new float[] {0f, 0.5f, 1f},
            new Color[] {alpha(GLOW, 0), alpha(GLOW, 170 * strength), alpha(GLOW, 0)}));
        g2.fillRect(x, (int) (cy - halo / 2.0), width, halo);
        g2.setColor(alpha(Color.WHITE, 255 * Math.min(1, strength * 1.3)));
        g2.fillRect(x, (int) Math.round(cy - LINE / 2.0), width, LINE);
    }

    /** The last glowing dot of a switched-off screen. */
    private static void dot(Graphics2D g2, double cx, double cy, double radius, double strength) {
        if (radius < 1 || strength <= 0) {
            return;
        }
        double a = 255 * Math.min(1, strength);
        g2.setPaint(new RadialGradientPaint((float) cx, (float) cy, (float) radius, new float[] {0f, 0.3f, 1f},
            new Color[] {alpha(Color.WHITE, a), alpha(GLOW, a * 0.55), alpha(GLOW, 0)}));
        g2.fillOval((int) Math.round(cx - radius), (int) Math.round(cy - radius), (int) Math.round(2 * radius), (int) Math.round(2 * radius));
    }

    // -- Losing the signal ---------------------------------------------
    /** Three stages: the old picture falls apart, snow covers the change, the new picture locks in from the top. */
    private void signalLoss(Graphics2D g2, int w, int h) {
        int stop = offEnd + STATIC;
        if (t < offEnd) {
            scene(g2, before, Math.pow(t / offEnd, 1.2), 1, 0, w, h);
        } else if (after == null || t < stop) {
            double u = Math.min(1, (t - offEnd) / STATIC);   // snow is thickest in the middle, where one picture gives way to the other
            scene(g2, u < 0.5 || after == null ? before : after, 1, u < 0.5 ? 1 : -1, Math.sin(Math.PI * u), w, h);
        } else {
            double p = Math.min(1, (t - stop) / LOCK);
            scene(g2, after, Math.pow(1 - p, 1.6), -1, 0, w, h);
            double sweep = easeInOut((p - 0.2) / 0.75);   // the part above the line has locked in
            int y = (int) Math.round(h * sweep);
            if (y > 0) {
                g2.setClip(0, 0, w, y);
                g2.drawImage(after, 0, 0, null);
                g2.setClip(null);
                glow(g2, w / 2.0, y, w / 2.0, Math.min(1, (1 - sweep) * 4));
            }
        }
    }

    /**
     * {@code image} as a TV that is losing its picture shows it. {@code a} (0 to 1) is how badly:
     * at 0 it is untouched. {@code roll} turns, and the direction of the roll is {@code dir}, so
     * that the new picture carries on in the way the old one went. {@code boost} is the burst of snow.
     */
    private void scene(Graphics2D g2, BufferedImage image, double a, int dir, double boost, int w, int h) {
        if (t - tornAt > 55) {
            tornAt = t;
            Arrays.fill(tear, 0);
            for (int i = (int) Math.round(a * 8); i > 0; i--) {
                int y = rnd.nextInt(h);
                int len = 3 + rnd.nextInt(8 + (int) (70 * a));
                int off = (rnd.nextBoolean() ? 1 : -1) * (8 + rnd.nextInt(24 + (int) (230 * a)));
                Arrays.fill(tear, y, Math.min(h, y + len), off);
            }
        }
        int roll = (int) Math.round(dir * 1.2 * h * a * a * a);
        warp(image, a, roll);
        g2.drawImage(warped, 0, 0, null);

        if (a > 0.25 && Math.abs(roll) > 6) {   // the dark bar between two pictures, with the line of light that draws the next one
            int seam = Math.floorMod(-roll, h);
            int bar = (int) (46 * a);
            g2.setPaint(new LinearGradientPaint(0, seam - bar, 0, seam + bar, new float[] {0f, 0.5f, 1f},
                new Color[] {alpha(Color.BLACK, 0), alpha(Color.BLACK, 235), alpha(Color.BLACK, 0)}));
            g2.fillRect(0, seam - bar, w, 2 * bar);
            glow(g2, w / 2.0, seam, w / 2.0, 0.65 * a);
        }
        for (int i = (int) (a * a * 22); i > 0; i--) {   // short bright dashes, a different handful every frame
            g2.setColor(alpha(rnd.nextBoolean() ? GLOW : Color.WHITE, 90 + rnd.nextInt(140)));
            g2.fillRect(rnd.nextInt(w), rnd.nextInt(h), 30 + rnd.nextInt(380), 1 + rnd.nextInt(3));
        }
        double noise = 0.3 * Math.pow(a, 1.5) + 0.7 * boost;
        if (noise > 0.01) {
            g2.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1, noise)));
            g2.setPaint(new TexturePaint(snow, new Rectangle(-rnd.nextInt(256), -rnd.nextInt(256), 256, 256)));
            g2.fillRect(0, 0, w, h);
        }
        double light = (rnd.nextDouble() - 0.4) * 0.35 * a + 0.45 * boost * boost * boost;   // the brightness is unsteady, and flares in the burst
        g2.setComposite(AlphaComposite.SrcOver);
        if (Math.abs(light) > 0.005) {
            g2.setColor(light > 0 ? alpha(Color.WHITE, 255 * light) : alpha(Color.BLACK, -255 * light));
            g2.fillRect(0, 0, w, h);
        }
        if (a > 0) {
            g2.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1, a)));
            g2.setPaint(scanlines);
            g2.fillRect(0, 0, w, h);
            g2.setComposite(AlphaComposite.SrcOver);
        }
    }

    /**
     * Fills {@link #warped} with {@code image} rolled up by {@code roll} rows, every row slid sideways
     * (a slow sway, plus the tears) and its red and blue pulled apart. A row slid off the edge comes back on the other side.
     */
    private void warp(BufferedImage image, double a, int roll) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[] from = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        int[] to = ((DataBufferInt) warped.getRaster().getDataBuffer()).getData();
        double sway = 18 * Math.pow(a, 1.5);
        int split = (int) Math.round(28 * Math.pow(a, 1.5));
        for (int y = 0; y < h; y++) {
            int src = Math.floorMod(y + roll, h) * w;
            int slide = (int) Math.round(sway * Math.sin(y * 0.045 + t * 0.02)) + tear[y];
            int apart = tear[y] == 0 ? split : 2 * split;   // a torn row comes apart more
            if (slide == 0 && apart == 0) {
                System.arraycopy(from, src, to, y * w, w);
                continue;
            }
            for (int x = 0, out = y * w; x < w; x++, out++) {
                to[out] = from[src + wrap(x + slide - apart, w)] & 0xFF0000
                    | from[src + wrap(x + slide, w)] & 0x00FF00
                    | from[src + wrap(x + slide + apart, w)] & 0x0000FF;
            }
        }
    }

    private static int wrap(int x, int w) {
        return x < 0 ? x + w : x >= w ? x - w : x;
    }

    /** A tile of snow: blue-white, in short runs along each row, the way a TV with no signal speckles. */
    private BufferedImage snow() {
        BufferedImage tile = new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256;) {
                double v = 255 * Math.pow(rnd.nextDouble(), 2.2);
                int rgb = (int) (v * 0.78) << 16 | (int) (v * 0.86) << 8 | (int) v;
                for (int run = 1 + rnd.nextInt(10); run > 0 && x < 256; run--, x++) {
                    tile.setRGB(x, y, rgb);
                }
            }
        }
        return tile;
    }

    private static Color alpha(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.max(0, Math.min(255, alpha)));
    }

    private static double easeIn(double p) {
        return p * p * p;
    }

    private static double easeOut(double p) {
        return 1 - Math.pow(1 - Math.min(1, Math.max(0, p)), 3);
    }

    private static double easeInOut(double p) {
        p = Math.max(0, Math.min(1, p));
        return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
    }
}
