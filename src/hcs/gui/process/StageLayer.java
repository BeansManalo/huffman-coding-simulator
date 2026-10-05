package hcs.gui.process;

import hcs.gui.Theme;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.plaf.LayerUI;

/**
 * Goes around the cubes and the table. It paints the dotted background under them, and over
 * them the spirits: a glowing copy of a cube that leaves it, grows as it flies in a curve to
 * its row of the table, and lands there.
 * <p>
 * It also paints the clones of the charge: a copy of a row of the table rises, shakes and breaks
 * into the 0s and 1s of its symbol (8 bits, the byte itself) and of its code. These fly to the
 * cannon and become motes at the outer edge of the energy swirling round its core (see
 * {@link CubeField#ORBIT}): each digit flies to where a mote begins, becomes it, and falls
 * into the core along the path the energy's motes take ({@link CubeField#mote}) at their pace,
 * so the core takes in exactly as many motes as there were digits.
 */
@SuppressWarnings("serial")
final class StageLayer extends LayerUI<JPanel> {

    /** Seconds a spirit takes to fly. */
    private static final double FLIGHT = 0.70;
    /** How big a spirit is when it lands, in pixels. */
    private static final int ICON = 26;
    /** How many fading copies follow a spirit. */
    private static final int TRAIL = 5;
    /** Seconds a clone takes to form before it breaks into the digits of its code. */
    private static final double CLONE = 0.34;
    /** How high a clone rises, in pixels. */
    private static final double LIFT = 26;
    /** The longest a digit is shown waiting at the clone after it breaks; one that must wait longer is not shown until this long before it leaves. */
    private static final double GATHER = 0.14;
    /** Seconds a digit takes to fly from the clone to the edge of the energy swirling round the core, where it becomes a mote. */
    private static final double HOP = 0.62;
    /** How many motes fall at the energy's own pace; more than this and they fall faster, to at most BOOST times as fast. */
    private static final double CROWD = 40;
    private static final double BOOST = 6;
    private static final Font DIGIT = Theme.FONT_EDITOR.deriveFont(Font.BOLD, 13f);
    /** The table's font for a symbol, and what text is measured with. */
    private static final Font SYMBOL = Theme.FONT_LABEL.deriveFont(14f);
    private static final BasicStroke STREAK = new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
    private static final FontRenderContext FRC = new FontRenderContext(null, true, true);

    private static final class Spirit {
        final int symbol;
        final int row;
        final double x;
        final double y;
        final double size;   // of the cube it comes from
        double age;

        Spirit(int symbol, int row, double x, double y, double size) {
            this.symbol = symbol;
            this.row = row;
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    /** One digit on its way into the core, which is one bit of the compressed size: of a code, or of a symbol's 8. It flies to where its mote begins, becomes it, and is absorbed. */
    private static final class Bit {
        final String digit;
        final Color color;
        final double delay;   // seconds it waits after the clone breaks, so the digits leave one by one
        final double dx;      // where it breaks off: how far along the code, from where the code starts
        final double fanX;
        final double fanY;
        double x;             // where it breaks off
        double y;
        int k;                // which mote it becomes: they are golden angle apart, so that they spread all round the core
        double age;           // seconds since it left the clone: HOP is when it becomes a mote
        double life;          // once a mote, how far down its fall it is: 0 at the orbit, 1 at the core (see CubeField#mote)

        Bit(Color color, boolean one, double delay, double dx, double fanX, double fanY) {
            this.digit = one ? "1" : "0";
            this.color = color;
            this.delay = delay;
            this.dx = dx;
            this.fanX = fanX;
            this.fanY = fanY;
        }
    }

    private static final class Clone {
        final int symbol;
        final String code;
        final Font font;       // the one the table draws this code in
        final Rectangle row;   // the row of the table it is a copy of
        final int codeX;       // where the code starts in the row
        final Bit[] bits;      // the 8 bits of the symbol, then the digits of the code
        final double life;     // seconds it takes to form and break
        double age;

        Clone(int symbol, String code, Font font, Rectangle row, int codeX, Bit[] bits, double life) {
            this.life = life;
            this.symbol = symbol;
            this.code = code;
            this.font = font;
            this.row = row;
            this.codeX = codeX;
            this.bits = bits;
        }
    }

    private final List<Spirit> flying = new ArrayList<>();
    private final List<Clone> clones = new ArrayList<>();
    private final List<Bit> bits = new ArrayList<>();
    private final IntFunction<Rectangle> target;
    private final IntConsumer landed;
    private final Supplier<Point> shift;
    private Supplier<Point> core = Point::new;
    private DoubleSupplier swirl = () -> 1;
    private int motes;   // how many digits have become motes, which sets where the next begins
    private Runnable orbited = () -> { };
    private Runnable absorbed = () -> { };
    private int orbiting;   // how many motes are falling at the moment
    private final double[][] trail = {new double[2], new double[2], new double[2]};   // scratch for drawing a bit: where it is, a moment ago, and a moment before that

    /**
     * @param target where a table row's symbol cell is, in this layer's coordinates, kept inside the visible part of the table
     * @param landed told the row of each spirit as it lands
     * @param shift how far the dotted background has been dragged
     */
    StageLayer(IntFunction<Rectangle> target, IntConsumer landed, Supplier<Point> shift) {
        this.target = target;
        this.landed = landed;
        this.shift = shift;
    }

    /**
     * @param core where the cannon's core is, in this layer's coordinates
     * @param swirl how fast the energy's motes fall, in turns of their fall per second
     * @param orbited told as each digit reaches where its mote begins and becomes it
     * @param absorbed told as each mote reaches the core: the digit, which is one bit of the compressed size, is taken in
     */
    void onBits(Supplier<Point> core, DoubleSupplier swirl, Runnable orbited, Runnable absorbed) {
        this.core = core;
        this.swirl = swirl;
        this.orbited = orbited;
        this.absorbed = absorbed;
    }

    /**
     * A clone of the table's row {@code row} (its box, with the code, drawn in {@code font},
     * starting at {@code codeX}) rises and breaks into the 8 bits of its {@code symbol}, which is
     * what the code stands for, and into every digit of {@code code}: each one bit of the
     * compressed size, flying to the cannon. The clones come {@code gap} seconds apart, and a quick
     * succession of them is quick to form too, so that they do not pile up. A clone's digits leave
     * one by one over the {@code gap} (at most 2 s, at least {@link #GATHER}), so that when there are few
     * clones the core takes the digits in steadily instead of all at once.
     */
    void cloneRow(int symbol, String code, Font font, Rectangle row, int codeX, double gap) {
        int m = 8 + code.length();
        double digitW = font.getStringBounds("0", FRC).getWidth();
        Bit[] list = new Bit[m];
        double spread = Math.max(GATHER, Math.min(2, gap));
        for (int k = 0; k < m; k++) {
            boolean mark = k < 8;   // the symbol's bits come first, high bit first, from the middle of its cell
            boolean one = mark ? (symbol >> (7 - k) & 1) == 1 : code.charAt(k - 8) == '1';
            double r1 = (k * 0.618034 + symbol * 0.37) % 1;
            double r2 = (k * 0.414214 + symbol * 0.71) % 1;
            list[k] = new Bit(mark ? Theme.TEXT_PRIMARY : one ? Theme.ACCENT_HOVER : Theme.VIOLET, one, spread * r1,
                mark ? (row.x - codeX - 8) / 2.0 + (k - 3.5) * digitW : (k - 8 + 0.5) * digitW,
                (k - (m - 1) / 2.0) * Math.min(12, 96.0 / m), 24 + 26 * r2);   // a long code fans out no wider than 8 digits did
        }
        clones.add(new Clone(symbol, code, font, row, codeX, list, Math.max(0.12, Math.min(CLONE, 3 * gap))));
    }

    /** A spirit leaves the cube at ({@code x}, {@code y}) of {@code size} pixels, for the table row {@code row}. */
    void launch(int symbol, int row, double x, double y, double size) {
        flying.add(new Spirit(symbol, row, x, y, size));
    }

    /** Moves the spirits on by {@code seconds}; the ones that arrive are removed and reported. */
    void advance(double seconds) {
        for (Iterator<Spirit> it = flying.iterator(); it.hasNext();) {
            Spirit s = it.next();
            s.age += seconds;
            if (s.age >= FLIGHT) {
                it.remove();
                landed.accept(s.row);
            }
        }
        for (Iterator<Clone> it = clones.iterator(); it.hasNext();) {
            Clone c = it.next();
            c.age += seconds;
            if (c.age >= c.life) {   // it breaks: the digits start from where it was
                for (Bit b : c.bits) {
                    b.x = c.codeX + b.dx;
                    b.y = c.row.getCenterY() - LIFT;
                    b.k = motes++;
                    b.age = -b.delay;
                    bits.add(b);
                }
                it.remove();
            }
        }
        double rate = swirl.getAsDouble() * Math.min(BOOST, Math.max(1, orbiting / CROWD));   // a crowd of motes falls faster, up to BOOST times
        for (Iterator<Bit> it = bits.iterator(); it.hasNext();) {
            Bit b = it.next();
            boolean flying = b.age < HOP;
            b.age += seconds;
            if (b.age >= HOP) {   // a mote now: it falls at the pace the energy's motes do
                if (flying) {
                    orbiting++;
                    orbited.run();
                }
                b.life += Math.min(seconds, b.age - HOP) * rate;
                if (b.life >= 1) {
                    it.remove();
                    orbiting--;
                    absorbed.run();
                }
            }
        }
    }

    boolean active() {
        return !flying.isEmpty() || !clones.isEmpty() || !bits.isEmpty();
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        g.setColor(Theme.CHROME_BG);
        g.fillRect(0, 0, c.getWidth(), c.getHeight());
        Point moved = shift.get();
        Theme.dots(g, c, moved.x, moved.y);
        super.paint(g, c);
        if (flying.isEmpty() && clones.isEmpty() && bits.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        for (Spirit s : flying) {
            draw(g2, s);
        }
        for (Clone copy : clones) {
            drawClone(g2, copy);
        }
        Point at = core.get();
        for (Bit b : bits) {
            drawBit(g2, b, at);
        }
        g2.dispose();
    }

    /** A clone: a glowing copy of a row of the table that rises, then shakes harder and fades as it breaks apart. */
    private void drawClone(Graphics2D g, Clone c) {
        double u = c.age / c.life;
        double rise = 1 - Math.pow(1 - Math.min(1, u * 1.4), 3);
        double breaking = Math.max(0, (u - 0.6) / 0.4);
        double alpha = 1 - breaking * breaking;
        Rectangle r = c.row;
        int x = (int) Math.round(r.x + Math.sin(u * 70) * 3 * breaking);
        int y = (int) Math.round(r.y - LIFT * rise);
        g.setColor(rgba(Theme.ACCENT, 40 * alpha));
        g.fillRect(x - 2, y - 2, r.width + 4, r.height + 4);
        g.setColor(rgba(Theme.ACCENT, 90 * alpha));
        g.fillRect(x, y, r.width, r.height);
        g.setColor(rgba(Theme.ACCENT_HOVER, 220 * alpha));
        g.drawRect(x, y, r.width - 1, r.height - 1);
        Shape clip = g.getClip();
        g.clipRect(x, y, r.width, r.height);
        g.setFont(SYMBOL);
        FontMetrics fm = g.getFontMetrics();
        String label = CubeField.label(c.symbol);
        g.setColor(rgba(CubeField.isSpecial(c.symbol) ? Theme.VIOLET : Color.WHITE, 255 * alpha));
        g.drawString(label, x + (c.codeX - 8 - r.x - fm.stringWidth(label)) / 2, y + (r.height - fm.getHeight()) / 2 + fm.getAscent());
        g.setFont(c.font);
        fm = g.getFontMetrics();
        g.setColor(rgba(Color.WHITE, 255 * alpha));
        g.drawString(c.code, x + c.codeX - r.x, y + (r.height - fm.getHeight()) / 2 + fm.getAscent());
        g.setClip(clip);
    }

    /**
     * A digit, and the mote it becomes. The digit flies to where its mote begins (see {@link #fly}),
     * arriving at rest as a mote does, and turns into it there the way the energy's motes appear: the
     * mote brightens in over the first fifth of its fall, and the digit shrinks away into it over the
     * same time. From then on it is drawn exactly as the energy's motes are: a dot, violet far out and
     * white at the core, with a streak behind it, falling in along {@link CubeField#mote}.
     */
    private void drawBit(Graphics2D g, Bit b, Point core) {
        if (b.age < -GATHER) {
            return;
        }
        double a = Math.max(0, b.age);
        double fade = Math.min(1, b.life * 5);   // the mote's own fade in, which is also how the digit turns into it
        if (fade < 1) {   // the digit and the glow it leaves
            double size = 1 - 0.6 * fade;
            double alpha = Math.min(1, 0.3 + 8 * a / HOP) * (1 - fade);
            for (int k = 2; k >= 0; k--) {
                if (a - 0.025 * k < 0) {
                    continue;
                }
                where(b, a - 0.025 * k, core, trail[k]);
                double[] at = trail[k];
                double r = (k == 0 ? 8 : 5) * size;
                g.setColor(rgba(b.color, (k == 0 ? 60 : 50 * (1 - k / 3.0)) * alpha));
                g.fill(new Ellipse2D.Double(at[0] - r, at[1] - r, 2 * r, 2 * r));
                if (k == 0) {
                    AffineTransform was = g.getTransform();
                    g.translate(at[0], at[1]);
                    g.scale(size, size);
                    g.setFont(DIGIT);
                    g.setColor(rgba(b.color, 255 * alpha));
                    g.drawString(b.digit, -4f, 5f);
                    g.setTransform(was);
                }
            }
        }
        if (b.life > 0) {   // the mote, as CubeField drew the energy's: a streak that fades away behind it, and a dot
            double[] at = trail[0];
            double[] was = trail[1];
            double[] older = trail[2];
            mote(b, b.life, core, at);
            mote(b, Math.max(0, b.life - 0.05), core, was);
            mote(b, Math.max(0, b.life - 0.10), core, older);
            double bright = fade * (0.25 + 0.75 * b.life) * (1 - smooth((b.life - 0.9) / 0.1));   // it melts into the core at the end
            Color hue = Theme.mix(Theme.VIOLET, Color.WHITE, b.life);
            g.setStroke(STREAK);
            g.setColor(rgba(hue, 255 * 0.3 * bright));
            g.draw(new Line2D.Double(older[0], older[1], was[0], was[1]));
            g.setColor(rgba(hue, 255 * 0.7 * bright));
            g.draw(new Line2D.Double(was[0], was[1], at[0], at[1]));
            g.setColor(rgba(hue, 255 * bright));
            double dot = 0.7 + 1.1 * b.life;
            g.fill(new Ellipse2D.Double(at[0] - dot, at[1] - dot, 2 * dot, 2 * dot));
        }
    }

    /** Where bit {@code b} is {@code a} seconds after it left the clone, in {@code out}: in flight, or a mote. */
    private void where(Bit b, double a, Point core, double[] out) {
        if (a < HOP) {
            fly(b, a / HOP, core, out);
        } else {
            mote(b, b.life, core, out);
        }
    }

    /** Where bit {@code b}'s mote is, {@code life} of the way down its fall, in {@code out} (in this layer's coordinates). */
    private static void mote(Bit b, double life, Point core, double[] out) {
        CubeField.mote(life, b.k, 0, out);
        out[0] += core.x;
        out[1] += core.y;
    }

    /**
     * Where bit {@code b} is {@code t} (0 to 1) of the way through its flight, in {@code out}. It goes
     * round the core, never over it, from where the clone broke to where its mote begins (the mote's
     * orbit and angle, which the flight turns towards the short way round), easing out of the clone
     * and into the orbit. It arrives at rest, which is how a mote begins, so the digit becomes the
     * mote with no stop, jump or kink. Its sideways fan dies away at both ends.
     */
    private void fly(Bit b, double t, Point core, double[] out) {
        double u = t * t * (3 - 2 * t);
        double x = b.x - core.x;
        double y = (b.y - core.y) / CubeField.SQUASH;   // the energy is a circle seen from an angle: this is it from above
        double from = Math.atan2(y, x);
        double turn = b.k * CubeField.GOLDEN - from;
        turn -= 2 * Math.PI * Math.round(turn / (2 * Math.PI));
        double r = Math.hypot(x, y);
        r += (CubeField.ORBIT - r) * u;
        double fan = 32 * Math.pow(t * (1 - t), 3);
        out[0] = core.x + Math.cos(from + turn * u) * r + fan * b.fanX;
        out[1] = core.y + Math.sin(from + turn * u) * r * CubeField.SQUASH - fan * b.fanY;
    }

    private static double smooth(double x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /** A spirit and its trail: the same flight a little earlier, dimmer each time. */
    private void draw(Graphics2D g, Spirit s) {
        Rectangle to = target.apply(s.row);
        double t = Math.min(1, s.age / FLIGHT);
        double x1 = to.getCenterX();
        double y1 = to.getCenterY();
        double bendX = (s.x + x1) / 2;
        double bendY = Math.min(s.y, y1) - 50 - Math.abs(x1 - s.x) * 0.12;   // the curve goes up and over
        double start = Math.max(4, Math.min(12, s.size * 0.6));
        for (int k = TRAIL; k >= 0; k--) {
            double tk = t - 0.035 * k;
            if (tk < 0) {
                continue;
            }
            double u = tk * tk * (3 - 2 * tk);
            double x = (1 - u) * (1 - u) * s.x + 2 * (1 - u) * u * bendX + u * u * x1;
            double y = (1 - u) * (1 - u) * s.y + 2 * (1 - u) * u * bendY + u * u * y1;
            double size = start + (ICON - start) * tk * tk;   // it grows, mostly near the end
            double alpha = Math.min(1, tk / 0.12) * (1 - k / (double) (TRAIL + 1));
            glow(g, x, y, size * 1.2 + 6, alpha);
            if (k == 0) {
                body(g, s.symbol, x, y, size, alpha);
            }
        }
    }

    private static void glow(Graphics2D g, double x, double y, double radius, double alpha) {
        g.setPaint(new RadialGradientPaint((float) x, (float) y, (float) radius, new float[] {0f, 0.45f, 1f},
            new Color[] {rgba(Theme.VIOLET, 170 * alpha), rgba(Theme.ACCENT, 70 * alpha), rgba(Theme.ACCENT, 0)}));
        g.fill(new Ellipse2D.Double(x - radius, y - radius, 2 * radius, 2 * radius));
    }

    private static void body(Graphics2D g, int symbol, double x, double y, double size, double alpha) {
        int r = (int) Math.round(size);
        int left = (int) Math.round(x - r / 2.0);
        int top = (int) Math.round(y - r / 2.0);
        g.setColor(rgba(Theme.ACCENT, 235 * alpha));
        g.fillRoundRect(left, top, r, r, r / 4, r / 4);
        g.setColor(rgba(Color.WHITE, 200 * alpha));
        g.drawRoundRect(left, top, r, r, r / 4, r / 4);
        if (r >= 14) {
            String label = CubeField.label(symbol);
            g.setFont(Theme.FONT_EDITOR.deriveFont(Font.BOLD, (float) (r * (label.length() == 1 ? 0.55 : 0.38))));
            g.setColor(Theme.CHROME_BG);
            FontMetrics fm = g.getFontMetrics();
            g.drawString(label, left + (r - fm.stringWidth(label)) / 2, top + (r - fm.getHeight()) / 2 + fm.getAscent());
        }
    }

    private static Color rgba(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.max(0, Math.min(255, alpha)));
    }
}
