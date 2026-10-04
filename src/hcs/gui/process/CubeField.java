package hcs.gui.process;

import hcs.core.FrequencyScan;
import hcs.core.FrequencyScan.Checkpoint;
import hcs.core.HuffmanTree;
import hcs.gui.Theme;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RadialGradientPaint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * The stage in the middle of the process screen. It has one cube per distinct byte, which
 * shows the byte and how often it occurs, and flashes orange each time it is counted. The
 * cubes start empty; the cannon's beam fills them.
 * <p>
 * First the cubes are in a centered grid, shrinking to keep everything in view. Then they
 * become the leaves of the Huffman tree: they line up in a row in order of count, and each
 * step of the build takes the two leftmost trees, joins them under a new violet cube that
 * shows both counts added, and slides the new tree to its place in the row. The finished tree
 * is drawn with its leaves in a row at the bottom and its root at the top.
 * <p>
 * Then a cannon over the root replays the input down the tree: the bytes of the part being read
 * flow along their codes as a beam (the common ones, wider the more common) or as single
 * electrons (the rare ones), and each cube fills as what reaches it is counted.
 * <p>
 * Once the tree is finished it can be zoomed in (the scroll wheel, or the control, which is
 * not in the stage but handed out by {@link #zoomBar()}) and dragged, but not out of view, and the
 * camera can be sent flying to a byte's cube ({@link #flyTo}). Up close a node is a whole cube; far away it is a flat
 * square, and in between the top and the side grow out of the square.
 */
@SuppressWarnings("serial")
final class CubeField extends JComponent {

    private static final int MAX_SIZE = 72;
    private static final int MIN_SIZE = 24;   // the smallest grid cube that still has room for its number
    /** Seconds a new cube takes to pop in. */
    private static final double POP = 0.38;
    /** Seconds a flash takes to die away. */
    private static final double FLASH = 0.30;
    /** Seconds the camera takes to fly to a byte's cube. */
    private static final double FLY = 0.45;
    /** Seconds the lines of a new node take to reach the nodes under it. */
    private static final double GROW = 0.30;

    // The tree is laid out in its own units: a node is NODE wide, a leaf has SLOT of room, and
    // each level of the tree is LEVEL high. The zoom turns those into pixels, so that any tree fits.
    private static final double NODE = 56;
    private static final double SLOT = 60;
    private static final double LEVEL = 90;
    private static final double MAX_ZOOM = 1.25;

    // The cannon over the root, and its beam.
    private static final double FEED_PX = 24;     // from the cannon's mouth down to the root, in pixels
    private static final double MUZZLE_H = 40;    // the gun under the cannon's box
    private static final double FEED = 60;        // the same way, in tree units: what the beam's speed is measured in
    private static final double TRAVEL = 1.4;     // seconds the beam takes to reach the deepest cube, however big the tree
    private static final int ELECTRONS = 360;     // at most this many electrons for a whole run: it stays smooth however big the input
    private static final int DENSE = 24;          // a byte that would need this many electrons is a beam instead

    // The stage's margins, and the zoom: the nodes can grow to MAX_CUBE pixels, and no further.
    private static final int EDGE = 16;
    private static final int TOP = 40;                    // under the caption
    private static final int BAR_W = 230;                 // the zoom control
    private static final int BAR_H = 34;
    private static final int THUMB = 24;
    private static final int BOTTOM = EDGE;
    private static final double MAX_CUBE = 96;
    /** A node smaller than this many pixels is a flat square; bigger than CUBE, a whole cube. */
    private static final double FLAT = 7;
    private static final double CUBE = 15;

    /**
     * A node: a byte, or (when {@code symbol} is -1) the node that joins two others. Where it
     * is and how big, in pixels of the grid or in units of the tree, glides to where it belongs.
     */
    private static final class Cube {
        final int symbol;
        int id;                  // its number in the tree
        int count;               // how often the byte occurs; for a joining node, both nodes' counts added
        int height;              // 0 for a byte; otherwise 1 more than the taller node under it
        Cube left, right;        // the two nodes a joining node joins
        double x = Double.NaN;   // the center; NaN until it has a place
        double y, tx, ty;        // ...and where it is headed
        double size, tsize;
        double age;              // seconds since it appeared
        double flash;            // 1 right after being counted, then fades to 0
        boolean selected;        // one of the two about to be joined
        double fill;             // how full the cube is shown (0 to 1), gliding to...
        double fillTo;           // ...how full it should be

        Cube(int symbol) {
            this.symbol = symbol;
        }
    }

    private static final Color FRONT = new Color(0x18, 0x21, 0x4A);
    private static final Color PLATE = new Color(0x0A, 0x0F, 0x26);
    private static final Color TOP_LOW = new Color(0x34, 0x47, 0x8F);
    private static final Color SIDE_LOW = new Color(0x0F, 0x17, 0x35);
    private static final Color JOINED_FRONT = Theme.mix(FRONT, Theme.VIOLET, 0.22);
    private static final Color JOINED_TOP = Theme.mix(TOP_LOW, Theme.VIOLET, 0.65);
    private static final Color JOINED_SIDE = Theme.mix(SIDE_LOW, Theme.VIOLET, 0.35);
    private static final Font BIT = Theme.FONT_EDITOR.deriveFont(Font.BOLD, 10f);

    private final Cube[] bySymbol = new Cube[256];
    private final List<Cube> cubes = new ArrayList<>();
    private final List<Cube> forest = new ArrayList<>();   // the roots of the trees so far, in order: the queue
    private HuffmanTree tree;                              // once set, the cubes are laid out as a tree
    private Cube[] node;                                   // the cube of each node of the tree
    private double zoom = 1;                               // tree units to pixels, across and for sizes; the grid is in pixels already
    private double zoomY = 1;                              // ...and up and down: a wide tree has its levels spread out
    private double panX;
    private double panY;
    private double baseZoom = 1;                           // the zoom and zoomY that just fit the tree...
    private double baseZoomY = 1;
    private double view = 1;                               // ...and how far the person has zoomed in from there
    private boolean interactive;                           // the tree is finished: the zoom and the dragging work
    private boolean dragging;                              // the tree is held
    private Cube target;                                   // while the camera flies: the cube it flies to
    private double flown;                                  // ...how far, 0 to 1
    private double fromX;                                  // ...and where that cube was on the stage, and how far in, when it set off
    private double fromY;
    private double fromView;
    private final ZoomBar zoomBar = new ZoomBar();
    private int lastX;
    private int lastY;
    private double dotX;                                   // how far the tree has been dragged: the dotted background goes with it
    private double dotY;
    private double reserve;                                // pixels kept clear above the tree, for the cannon
    private double cannonShow;                             // the cannon's mouth: 0 not there, 1 fully there
    private double charge;                                 // 0 to 1, how charged the gun is: its rings, its core
    private double blast;                                  // 1 as it fires, dying away: the flash and the kick
    private double clock;                                  // seconds, for what spins
    private double spin;                                   // how far the core's sparks have turned (radians): kept, not worked out from the clock, so a change of charge cannot make them jump
    private double swirl;                                  // how far the energy being taken in has moved, in turns, kept for the same reason
    private boolean charging;                              // the gun is taking energy in
    private double absorb;                                 // ...and how strongly that shows, 0 to 1
    private double claw = 0.85;                            // how far the claw is open: 0 closed on the core, 1 spread wide
    private double power = 1;                              // how big the beam is, 0.25 to 1, from the size of the input
    private int cannonW;
    private int cannonH;
    private double maxCount;                               // the count of the commonest byte: a full cube
    private Beam beam;                                     // while the cannon is firing
    private final double[] rx = new double[4];             // the corners of one line, for the beam
    private final double[] ry = new double[4];
    private Runnable panListener = () -> { };
    private double glide = 0.08;                           // seconds a cube takes to cover most of its way
    private boolean dirty;                                 // the cubes must be laid out again
    private boolean snap;                                  // ...and jump to their places instead of gliding
    private int laidOutWidth;
    private int laidOutHeight;
    private String caption = "";
    private String message;
    private boolean messageIsError;
    private int fontSize = -1;
    private Font big;
    private Font small;
    private Font tiny;
    private Font num;

    CubeField() {
        setOpaque(false);
        setToolTipText("");   // switches tooltips on; the text comes from getToolTipText(MouseEvent)
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (free() && SwingUtilities.isLeftMouseButton(e)) {
                    lastX = e.getX();
                    lastY = e.getY();
                    dragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (dragging) {
                    pan(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX();
                    lastY = e.getY();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
                setCursor(Cursor.getDefaultCursor());
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                if (free()) {
                    setView(view * Math.pow(1.2, -e.getPreciseWheelRotation()), e.getX(), e.getY());
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    // -- The grid ------------------------------------------------------
    /** Shows every cube at once, most common first. */
    void showAll(FrequencyScan scan) {
        message = null;
        int[] counts = scan.counts();
        for (int symbol : scan.firstSeen()) {
            Cube cube = new Cube(symbol);
            cube.count = counts[symbol];
            cube.age = POP;
            bySymbol[symbol] = cube;
            cubes.add(cube);
        }
        sort();
        snap = true;
    }

    /** Brings the cubes up to date with {@code counts}: new bytes pop in, bytes counted again flash. */
    void update(int[] counts, int[] firstSeen) {
        message = null;
        for (int symbol : firstSeen) {
            if (counts[symbol] == 0) {
                break;   // firstSeen is in order of appearance, so nothing after this has been read yet
            }
            Cube cube = bySymbol[symbol];
            if (cube == null) {
                cube = new Cube(symbol);
                bySymbol[symbol] = cube;
                cubes.add(cube);
                dirty = true;
            } else if (counts[symbol] > cube.count) {
                cube.flash = 1;
            }
            cube.count = counts[symbol];
        }
    }

    /** Orders the grid most common first (ties by byte value); the cubes glide to their new places. */
    void sort() {
        cubes.sort(Comparator.comparingInt((Cube c) -> -c.count).thenComparingInt(c -> c.symbol));
        dirty = true;
    }

    void setCaption(String caption) {
        this.caption = caption;
    }

    /** Text in the middle of the stage, in place of the cubes. */
    void setMessage(String message, boolean isError) {
        this.message = message;
        this.messageIsError = isError;
    }

    /** How long (in seconds) cubes take to cover most of the way to their places. */
    void setGlide(double seconds) {
        glide = seconds;
    }

    // -- The tree ------------------------------------------------------
    /** The finished tree, everything in its place at once. Call after {@link #showAll}. */
    void showTree(HuffmanTree tree) {
        enterTree(tree, true);
        for (int i = 0; i < tree.merges().length; i++) {
            join(i);
            slide(i);
        }
        for (Cube c : cubes) {
            c.age = POP + GROW;
        }
    }

    /** The animated build begins: the cubes leave the grid for a row in order of count. */
    void startTree(HuffmanTree tree) {
        enterTree(tree, false);
    }

    private void enterTree(HuffmanTree t, boolean snapNow) {
        tree = t;
        message = null;
        node = new Cube[t.nodeCount()];
        forest.clear();
        for (int id = 0; id < t.leafCount(); id++) {
            Cube c = bySymbol[t.symbol(id)];
            c.id = id;
            node[id] = c;
            forest.add(c);   // the leaves are numbered least common first, which is the order of the queue
        }
        maxCount = t.weight(t.leafCount() - 1);   // the leaves are numbered least common first
        fitCamera();
        for (Cube c : cubes) {   // what is on show moves to tree units, staying where it is on the screen
            c.x = (c.x - panX) / zoom;
            c.y = (c.y - panY) / zoomY;
            c.size /= zoom;
        }
        snap = snapNow;
        dirty = true;
    }

    /** Step 1 of a merge: the two smallest trees, the first two of the queue, are picked out. */
    void select(int merge) {
        forest.get(0).selected = true;
        forest.get(1).selected = true;
    }

    /** Step 2: a new node appears over the two, joined to them by lines. */
    void join(int merge) {
        HuffmanTree.Merge m = tree.merges()[merge];
        Cube a = node[m.left()];
        Cube b = node[m.right()];
        Cube p = new Cube(-1);
        p.id = m.parent();
        p.count = tree.weight(p.id);
        p.height = tree.height(p.id);
        p.left = a;
        p.right = b;
        a.selected = false;
        b.selected = false;
        node[p.id] = p;
        cubes.add(p);
        forest.set(0, p);
        forest.remove(1);
        layoutForest();
    }

    /** Step 3: the new tree slides to its place in the queue, which is in order of count. */
    void slide(int merge) {
        Cube p = node[tree.merges()[merge].parent()];
        forest.remove(p);
        int at = 0;
        while (at < forest.size() && before(forest.get(at), p)) {
            at++;
        }
        forest.add(at, p);
        layoutForest();
    }

    private static boolean before(Cube a, Cube b) {
        return a.count != b.count ? a.count < b.count : a.id < b.id;
    }

    /** A byte's cube flashes: its spirit leaves it. */
    void release(int symbol) {
        bySymbol[symbol].flash = 1;
    }

    /** Where a byte's cube is: its center and its size, in pixels of this component. */
    double[] leafBounds(int symbol) {
        Cube c = bySymbol[symbol];
        return new double[] {panX + c.x * zoom, panY + c.y * zoomY, c.size * zoom};
    }

    // -- The cannon and its beam ---------------------------------------
    /** The size of the cannon's box: {@link #cannonBounds()} puts it over the root. */
    void setCannonSize(int width, int height) {
        cannonW = width;
        cannonH = height;
    }

    /** How far the cannon is in (0 to 1): the tree makes room above itself, and the cannon's mouth shows. */
    void setCannon(double show) {
        cannonShow = show;
        reserve = show * (cannonH + FEED_PX + MUZZLE_H - 8);
        if (tree != null) {
            fitCamera();
        }
        repaint();
    }

    /** The glow in the cannon's mouth, 0 (none) to 1 (as bright as when it fires). */
    void setCharge(double charge) {
        this.charge = charge;
    }

    /** How fast the energy being taken in moves, in turns of a mote's fall per second: faster as the gun charges. */
    double swirlRate() {
        return 0.8 + 1.4 * charge;
    }

    /** Whether the gun is taking energy in: while it is, energy streams from all round into its core. */
    void setCharging(boolean charging) {
        this.charging = charging;
    }

    /** Where the cannon's core is, as (x, y) in this component's coordinates: what the energy swirls into, and the digits of the charge fly to. */
    double[] core() {
        double[] at = muzzle();
        return new double[] {at[0], at[1] + MUZZLE_H - 2};
    }

    /** How far out the energy being taken in starts, and how flat it is seen: the edge the digits of the charge fly to (see StageLayer). */
    static final double ORBIT = 85;
    static final double SQUASH = 0.55;

    /** The top middle of the gun, as (x, y): the box's bottom edge, over the root. */
    private double[] muzzle() {
        Cube root = node[tree.root()];
        double rs = root.size * zoom;
        return new double[] {panX + root.x * zoom - rs * 0.07, panY + root.y * zoomY - rs / 2 - FEED_PX - MUZZLE_H};
    }

    /** Where the cannon's box goes: over the root, with the mouth just above it. */
    Rectangle cannonBounds() {
        Cube root = node[tree.root()];
        double rs = root.size * zoom;
        double x = panX + root.x * zoom - rs * 0.07;
        double mouth = panY + root.y * zoomY - rs / 2 - FEED_PX;
        int left = (int) Math.round(Math.max(8, Math.min(getWidth() - 8 - cannonW, x - cannonW / 2.0)));
        return new Rectangle(left, (int) Math.round(mouth - MUZZLE_H) - cannonH, cannonW, cannonH);
    }

    /** The cannon starts to fire: it replays the input, {@code checkpoints} of it, over {@code seconds}. */
    void arm(Checkpoint[] checkpoints, double seconds) {
        double bytes = checkpoints[checkpoints.length - 1].position();
        power = Math.max(0.25, Math.min(1, 0.25 + 0.75 * (Math.log10(Math.max(1, bytes)) - 1.5) / 3.5));   // 30 bytes or less the smallest, 100 KB or more the biggest
        blast = 1;
        beam = new Beam(checkpoints, seconds);
    }

    /** Moves the beam on by {@code seconds}. */
    void fire(double seconds) {
        beam.step(Math.min(seconds, 0.1));   // a stall must not make the electrons jump
    }

    /** How much of the input the cannon has replayed, 0 to 1. */
    double fireProgress() {
        return Math.min(1, beam.t / beam.seconds);
    }

    /** Whether anything is still on its way down the tree: the last of the input needs {@link #TRAVEL} to arrive. */
    boolean beamBusy() {
        return beam.t < beam.seconds + TRAVEL + 0.05;
    }

    /** How many bytes of the input have reached their cubes, and how many bits of code they stand for. */
    double arrivedBytes() {
        return beam == null ? 0 : beam.bytes;
    }

    double arrivedBits() {
        return beam == null ? 0 : beam.bits;
    }

    /** Of those bits, the ones past the first copy of each byte's code, which is all the table (and so the charge) has counted. */
    double arrivedRepeatBits() {
        return beam == null ? 0 : beam.repeats;
    }

    /** Everything has arrived: the beam is gone and each cube is as full as it gets. */
    void endBeam() {
        fillAll();
        beam = null;
    }

    /** Every cube as full as it gets, at once (what the beam ends up with). */
    void fillAll() {
        for (Cube c : cubes) {
            if (c.symbol >= 0) {
                c.fillTo = fillOf(c.count);
                c.fill = c.fillTo;
            }
        }
    }

    /**
     * How full a cube is once {@code seen} of its byte have arrived. Not in proportion to the
     * count (a rare byte would stay empty): on a log scale, so that the commonest byte fills its
     * cube and a rare one still shows.
     */
    private double fillOf(double seen) {
        return Math.log1p(seen) / Math.log1p(maxCount);
    }

    /**
     * The gun under the cannon's box, over the root, in flat faces like the cubes: a shoulder and a
     * tapering barrel (lit on the left, darker on the right) whose three rings light up as it
     * charges, a claw of three prongs round a glowing core, and sparks that circle the core faster
     * the more it is charged. While it charges, the digits of the charge enter orbit round it as motes
     * and are drawn into the core, and the claw closes on it, trembling once it is full. Firing throws the claw open,
     * flashes the core, sends rings out of it and kicks the barrel back, and while the beam runs,
     * pulses leave the mouth with it. Its status light goes from orange to blue as it charges (as a
     * portal gun's two colors do).
     */
    private void paintMuzzle(Graphics2D g) {
        double[] at = muzzle();
        double x = at[0];
        double top = at[1];
        double kick = 6 * blast * blast;
        Color lit = Theme.mix(TOP_LOW, Theme.TEXT_PRIMARY, 0.30);
        Color metal = Theme.mix(TOP_LOW, Theme.TEXT_PRIMARY, 0.12);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(1, cannonShow)));
        g2.translate(x, top - kick);   // the barrel slides back into its box; the core stays

        Path2D.Double shoulder = polygon(-54, 0, 54, 0, 38, 11, -38, 11);
        g2.setColor(metal);
        g2.fill(shoulder);
        g2.setColor(Theme.DIVIDER);
        g2.draw(shoulder);
        g2.setColor(Theme.mix(Theme.CARET, Theme.ACCENT_HOVER, Math.min(1, charge * 4)));   // the status light
        g2.fillRect(-8, 3, 16, 3);

        Path2D.Double left = polygon(-38, 11, 0, 11, 0, 27, -21, 27);
        Path2D.Double right = polygon(0, 11, 38, 11, 21, 27, 0, 27);
        g2.setColor(lit);
        g2.fill(left);
        g2.setColor(PLATE);
        g2.fill(right);
        g2.setColor(Theme.DIVIDER);
        g2.draw(left);
        g2.draw(right);
        for (int i = 0; i < 3; i++) {   // the rings light up one after the other
            double lit1 = Math.max(0, Math.min(1, charge * 3.4 - i));
            double y = 15 + i * 5;
            double half = 38 - (y - 11) * 1.0625 - 4;
            Line2D.Double ring = new Line2D.Double(-half, y, half, y);
            if (lit1 > 0) {
                g2.setColor(fade(Theme.ACCENT, 0.25 * lit1));
                g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                g2.draw(ring);
            }
            g2.setColor(Theme.mix(Theme.DIVIDER, Theme.ACCENT_HOVER, lit1));
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            g2.draw(ring);
        }
        paintClaw(g2, lit, metal);

        g2.translate(0, kick);
        double cy = MUZZLE_H - 2;   // the core, between the claw's tips: where the beam starts
        paintAbsorb(g2, cy);
        double scale = beam == null ? 1 : 0.6 + 0.4 * power;
        float r = (float) ((4 + 8 * charge) * scale + 14 * blast * power + 1.3 * absorb * Math.sin(clock * 9));
        g2.setPaint(new RadialGradientPaint(0, (float) cy, r + 8, new float[] {0f, 0.3f, 0.65f, 1f},
            new Color[] {fade(Color.WHITE, 0.35 + 0.65 * charge), fade(Theme.ACCENT_HOVER, 0.85 * Math.max(charge, 0.25)),
                fade(Theme.VIOLET, 0.35 * Math.max(charge, 0.25)), fade(Theme.ACCENT, 0)}));
        g2.fill(new Ellipse2D.Double(-r - 8, cy - r - 8, 2 * r + 16, 2 * r + 16));
        g2.setStroke(new BasicStroke(1.2f));   // a thin rim round the core, like a portal's
        g2.setColor(fade(Theme.ACCENT_HOVER, 0.35 + 0.65 * Math.max(charge, blast)));
        g2.draw(new Ellipse2D.Double(-r * 0.75, cy - r * 0.75, 1.5 * r, 1.5 * r));
        for (int k = 0; k < 5; k++) {   // sparks circling the core
            double a = spin + k * 2 * Math.PI / 5;
            double depth = Math.sin(a);
            double dot = depth > 0 ? 1.8 : 1.2;
            g2.setColor(fade(Color.WHITE, charge * (depth > 0 ? 0.9 : 0.4)));
            g2.fill(new Ellipse2D.Double(Math.cos(a) * (13 + 7 * charge) - dot, cy + depth * 5 - dot, 2 * dot, 2 * dot));
        }
        if (beam != null) {   // while it fires, a pulse leaves the mouth every moment, down the way the beam goes
            g2.setStroke(new BasicStroke(1.4f));
            for (int k = 0; k < 3; k++) {
                double life = (clock * 2.4 + k / 3.0) % 1;
                double wide = (9 + 9 * power) * (0.6 + 0.8 * life);
                g2.setColor(fade(Theme.ACCENT_HOVER, 0.7 * (1 - life)));
                g2.draw(new Ellipse2D.Double(-wide, cy + 4 + life * (FEED_PX - 8) - wide * 0.3, 2 * wide, wide * 0.6));
            }
        }
        if (blast > 0) {
            double bloom = (4 + 8 * power) * blast;   // a flash down the beam's way, brightest at the mouth
            g2.setPaint(new GradientPaint(0, (float) cy, fade(Color.WHITE, 0.85 * blast), 0, (float) (cy + FEED_PX + 14), fade(Theme.ACCENT, 0)));
            g2.fill(new Rectangle.Double(-bloom, cy, 2 * bloom, FEED_PX + 14));
            for (int w = 0; w < 2; w++) {   // two shock rings, the second a beat after the first
                double u = Math.min(1, Math.max(0, (1 - blast - 0.22 * w) / (1 - 0.22 * w)));
                if (u <= 0 || u >= 1) {
                    continue;
                }
                double ring = 12 + 84 * (1 - Math.pow(1 - u, 3)) * (0.5 + 0.5 * power);
                g2.setStroke(new BasicStroke((float) (1 + 3 * (1 - u))));
                g2.setColor(fade(Theme.ACCENT_HOVER, 0.85 * (1 - u)));
                g2.draw(new Ellipse2D.Double(-ring, cy - ring * 0.36, 2 * ring, ring * 0.72));
            }
            g2.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.setColor(fade(Color.WHITE, 0.75 * blast * blast));
            for (int k = 0; k < 8; k++) {   // a starburst, long rays and short between
                double a = k * Math.PI / 4 + 0.2;
                double len = (6 + 30 * blast) * (0.5 + 0.5 * power) * (k % 2 == 0 ? 1 : 0.55);
                g2.draw(new Line2D.Double(Math.cos(a) * r * 0.5, cy + Math.sin(a) * r * 0.5, Math.cos(a) * len, cy + Math.sin(a) * len));
            }
        }
        g2.dispose();
    }

    /**
     * The claw: three prongs hinged to the foot of the barrel, two at the sides and one behind the
     * core. {@link #claw} says how far they are open: shut they grip the core (the side prongs bend
     * in at the hinge, the rear one stands out below it), open they are spread wide and clear of the
     * beam. They tremble when it is fully charged, and their hinges and tips light up as it charges.
     */
    private void paintClaw(Graphics2D g2, Color lit, Color metal) {
        double open = Math.min(1.15, claw + 0.15 * blast);   // thrown a little past wide by the shot
        double shake = beam == null ? charge * charge * charge * Math.sin(spin * 9) * 1.1 : 0;
        double glow = Math.max(charge, blast);
        Path2D.Double rear = bar(0, 25, 0, 25 + 24 - 11 * open, 9, 2);   // shorter as it opens: it swings toward the viewer
        g2.setColor(PLATE);
        g2.fill(rear);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(Theme.DIVIDER);
        g2.draw(rear);
        for (int side = -1; side <= 1; side += 2) {
            double a1 = Math.toRadians(10 + 52 * open);   // the upper arm, from straight down
            double a2 = Math.toRadians(-52 + 74 * open);  // the tip, bent in against the arm when shut
            double px = side * 17 + shake;
            double py = 27;
            double ex = px + side * Math.sin(a1) * 11;
            double ey = py + Math.cos(a1) * 11;
            double tx = ex + side * Math.sin(a2) * 15;
            double ty = ey + Math.cos(a2) * 15;
            Color face = side < 0 ? lit : metal;
            Path2D.Double arm = bar(px, py, ex, ey, 8, 6);
            Path2D.Double tip = bar(ex, ey, tx, ty, 6, 1);
            g2.setColor(face);
            g2.fill(arm);
            g2.fill(tip);
            g2.setColor(Theme.DIVIDER);
            g2.draw(arm);
            g2.draw(tip);
            Ellipse2D.Double hinge = new Ellipse2D.Double(ex - 3.4, ey - 3.4, 6.8, 6.8);
            g2.setColor(face);
            g2.fill(hinge);
            g2.setColor(Theme.DIVIDER);
            g2.draw(hinge);
            g2.setColor(fade(Theme.ACCENT_HOVER, 0.35 + 0.65 * glow));
            g2.fill(new Ellipse2D.Double(ex - 1.4, ey - 1.4, 2.8, 2.8));
            g2.setPaint(new RadialGradientPaint((float) tx, (float) ty, 5, new float[] {0f, 1f},
                new Color[] {fade(Theme.ACCENT_HOVER, 0.75 * glow), fade(Theme.ACCENT_HOVER, 0)}));
            g2.fill(new Ellipse2D.Double(tx - 5, ty - 5, 10, 10));
        }
    }

    /** The angle between one mote and the next, in radians (the golden angle). */
    static final double GOLDEN = 2.399963;

    /**
     * Where a mote of absorbed energy is, {@code life} (0 far out, 1 at the core): golden angle apart,
     * swirling as it falls in, in {@code out}, from the core's own centre at {@code cy}. Slowly at
     * first and faster as it nears the core. The motes are the digits of the charge (see StageLayer),
     * which fall along this once they arrive.
     */
    static void mote(double life, int k, double cy, double[] out) {
        double dist = 9 + (ORBIT - 9) * (1 - life * life);
        double angle = k * GOLDEN + 2.2 * life * life;
        out[0] = Math.cos(angle) * dist;
        out[1] = cy + Math.sin(angle) * dist * SQUASH;
    }

    /**
     * The energy being taken in: a glow round the core, and rings that shrink onto it. Both fade in and
     * out with {@link #absorb}, so they begin only once the first digit of the charge has reached the
     * edge. The motes are not drawn here: each is a digit of the charge, which becomes one at the edge
     * and falls into the core (see StageLayer), so there are exactly as many as there are digits.
     */
    private void paintAbsorb(Graphics2D g2, double cy) {
        if (absorb < 0.01) {
            return;
        }
        g2.setPaint(new RadialGradientPaint(0, (float) cy, 46, new float[] {0f, 1f},
            new Color[] {fade(Theme.ACCENT, 0.24 * absorb * (0.4 + 0.6 * charge)), fade(Theme.ACCENT, 0)}));
        g2.fill(new Ellipse2D.Double(-46, cy - 46, 92, 92));
        g2.setStroke(new BasicStroke(1.2f));
        for (int k = 0; k < 2; k++) {
            double life = (swirl * 0.5 + k * 0.5) % 1;
            double ring = 10 + 54 * (1 - life);
            g2.setColor(fade(Theme.ACCENT_HOVER, absorb * 0.55 * life));
            g2.draw(new Ellipse2D.Double(-ring, cy - ring * 0.5, 2 * ring, ring));
        }
    }

    /** A straight bar from ({@code x0}, {@code y0}), {@code w0} wide, to ({@code x1}, {@code y1}), {@code w1} wide. */
    private static Path2D.Double bar(double x0, double y0, double x1, double y1, double w0, double w1) {
        double len = Math.hypot(x1 - x0, y1 - y0);
        double nx = -(y1 - y0) / len;
        double ny = (x1 - x0) / len;
        return polygon(x0 + nx * w0 / 2, y0 + ny * w0 / 2, x1 + nx * w1 / 2, y1 + ny * w1 / 2,
            x1 - nx * w1 / 2, y1 - ny * w1 / 2, x0 - nx * w0 / 2, y0 - ny * w0 / 2);
    }

    private static Path2D.Double polygon(double... xy) {
        Path2D.Double path = new Path2D.Double();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) {
            path.lineTo(xy[i], xy[i + 1]);
        }
        path.closePath();
        return path;
    }

    /** An electron: {@code weight} bytes of one byte value, on their way down its code. */
    private static final class Electron {
        final int leaf;
        final int weight;
        int k;          // which line of its path it is on: 0 is the cannon's, then one per step down the tree
        double s;       // how far along that line, in tree units

        Electron(int leaf, int weight) {
            this.leaf = leaf;
            this.weight = weight;
        }
    }

    /**
     * One firing: the input replayed, as flow down the tree, over a few seconds. What is being
     * read decides what flows: each byte's share of the part around the point being read.
     * <p>
     * A byte common enough to need {@link #DENSE} electrons or more is a beam along its code,
     * wider the bigger its share. A rarer one is sent as electrons, each standing for an equal
     * share of its count and fired once part of that share has been read (a different part for each
     * byte, so that the rare ones do not all land at once); so a rare byte
     * is seen on its own, a common one is a stream, and the number of electrons never goes over
     * {@link #ELECTRONS} however big the input is. A byte counts as arrived when its electron
     * lands, or, for a beam, as the beam brings it.
     * <p>
     * Everything moves at one speed, in tree units a second, chosen so that the deepest cube is
     * reached in {@link #TRAVEL} seconds. Every line is numbered by the node it leads into; the
     * root's is the one from the cannon.
     */
    private final class Beam {

        private final Checkpoint[] cps;
        private final double seconds;      // the replay takes
        private final int leaves;
        private final int[] parent;
        private final int[][] path;        // the nodes from the root down to each leaf
        private final double[] len;        // of the line into each node, in tree units
        private final double[] start;      // how far from the cannon that line begins
        private final double speed;
        private final double reach;        // how far from the cannon the deepest cube is: what the beam's color shifts over
        private final int[] symbol;        // of each leaf
        private final int[] total;         // how often it occurs
        private final int[] codeBits;
        private final int[] quanta;        // how many electrons it is sent as; 0 for a beam
        private final double[] offset;     // how far into its share an electron of each leaf leaves, 0 to 1: spread out, so the rare bytes do not all land together
        private final int[] sent;
        private final double[] tau;        // when a beam's first bytes get to its cube
        private final double[] seen;       // how much of it has arrived
        private final double[] share;      // of what is being read, how much goes through each node as a beam
        private final List<Electron> flying = new ArrayList<>();
        private final List<double[]> hits = new ArrayList<>();   // an electron that reached its cube: the cube and how long ago
        private final Ellipse2D.Double dot = new Ellipse2D.Double();
        private final double[] pt = new double[2];
        private double t;                  // seconds since firing began
        private double cut = -1;           // when the input ran out; the beam's tail leaves then
        private double bytes;
        private double bits;
        private double repeats;            // the bits of the bytes that have arrived beyond the first of each: the table has the first

        Beam(Checkpoint[] cps, double seconds) {
            this.cps = cps;
            this.seconds = seconds;
            leaves = tree.leafCount();
            int nodes = tree.nodeCount();
            int root = tree.root();
            parent = new int[nodes];
            parent[root] = -1;
            for (HuffmanTree.Merge m : tree.merges()) {
                parent[m.left()] = m.parent();
                parent[m.right()] = m.parent();
            }
            len = new double[nodes];
            start = new double[nodes];
            len[root] = FEED;
            for (int n = nodes - 2; n >= 0; n--) {   // a node is numbered before its parent, so counting down goes down the tree
                Cube c = node[n];
                Cube p = node[parent[n]];
                len[n] = Math.abs(c.tx - p.tx) + Math.abs(c.ty - p.ty);
                start[n] = start[parent[n]] + len[parent[n]];
            }
            path = new int[leaves][];
            symbol = new int[leaves];
            total = new int[leaves];
            codeBits = new int[leaves];
            quanta = new int[leaves];
            offset = new double[leaves];
            sent = new int[leaves];
            tau = new double[leaves];
            seen = new double[leaves];
            share = new double[nodes];
            Checkpoint last = cps[cps.length - 1];
            double budget = Math.min(last.position(), ELECTRONS);
            double far = 0;
            for (int l = 0; l < leaves; l++) {
                int depth = 0;
                for (int n = l; n >= 0; n = parent[n]) {
                    depth++;
                }
                path[l] = new int[depth];
                for (int n = l, i = depth - 1; i >= 0; n = parent[n], i--) {
                    path[l][i] = n;
                }
                far = Math.max(far, start[l] + len[l]);
                symbol[l] = tree.symbol(l);
                total[l] = last.counts()[symbol[l]];
                codeBits[l] = tree.code(symbol[l]).length();
                int q = Math.max(1, (int) Math.round(total[l] * budget / last.position()));
                quanta[l] = q >= DENSE ? 0 : q;
                offset[l] = 0.05 + 0.9 * ((0.5 + l * 0.6180339887) % 1);   // the golden ratio spreads them evenly
            }
            reach = far;
            speed = far / TRAVEL;
            for (int l = 0; l < leaves; l++) {
                tau[l] = (start[l] + len[l]) / speed;
            }
        }

        /** How many of a byte have been read when the replay is {@code fi} checkpoints in. */
        private double cumulative(int leaf, double fi) {
            int i = Math.max(0, Math.min(cps.length - 2, (int) fi));
            double f = Math.max(0, Math.min(1, fi - i));
            int a = cps[i].counts()[symbol[leaf]];
            return a + f * (cps[i + 1].counts()[symbol[leaf]] - a);
        }

        void step(double dt) {
            t += dt;
            double p = Math.min(1, t / seconds);
            if (cut < 0 && p >= 1) {
                cut = seconds;
            }
            double fi = p * (cps.length - 1);

            // What is being read: each byte's share of the stretch around the point reached.
            int window = Math.max(1, (int) Math.round(0.04 * (cps.length - 1)));
            Checkpoint from = cps[Math.max(0, (int) fi - window)];
            Checkpoint to = cps[Math.min(cps.length - 1, (int) Math.ceil(fi) + window)];
            double span = to.position() - from.position();
            Arrays.fill(share, 0);
            for (int l = 0; l < leaves; l++) {
                if (quanta[l] == 0 && span > 0) {
                    share[l] = (to.counts()[symbol[l]] - from.counts()[symbol[l]]) / span;
                }
            }
            for (HuffmanTree.Merge m : tree.merges()) {
                share[m.parent()] = share[m.left()] + share[m.right()];
            }

            // A byte's electrons each stand for an equal share of it, and leave once part of that share has been read.
            for (int l = 0; l < leaves; l++) {
                while (sent[l] < quanta[l]) {
                    long k = sent[l];
                    long q = quanta[l];
                    long all = total[l];
                    if (cumulative(l, fi) < Math.ceil((k + offset[l]) * all / q)) {
                        break;
                    }
                    flying.add(new Electron(l, (int) ((k + 1) * all / q - k * all / q)));
                    sent[l]++;
                }
            }
            for (Iterator<Electron> it = flying.iterator(); it.hasNext();) {
                Electron e = it.next();
                int[] way = path[e.leaf];
                e.s += speed * dt;
                while (e.k < way.length && e.s >= len[way[e.k]]) {
                    e.s -= len[way[e.k++]];
                }
                if (e.k == way.length) {   // it has reached its cube
                    seen[e.leaf] += e.weight;
                    node[e.leaf].flash = 1;
                    if (hits.size() < 160) {
                        hits.add(new double[] {e.leaf, 0});
                    }
                    it.remove();
                }
            }
            for (Iterator<double[]> it = hits.iterator(); it.hasNext();) {
                if ((it.next()[1] += dt) > 0.4) {
                    it.remove();
                }
            }

            bytes = 0;
            bits = 0;
            repeats = 0;
            for (int l = 0; l < leaves; l++) {
                Cube c = node[l];
                if (quanta[l] == 0) {   // a beam brings its byte as it reaches the cube, as it was read
                    double late = t - tau[l];
                    seen[l] = cumulative(l, Math.max(0, Math.min(1, late / seconds)) * (cps.length - 1));
                    if (late >= 0 && late < seconds && share[l] > 0.004) {
                        c.flash = Math.max(c.flash, 0.6);
                    }
                }
                c.fillTo = fillOf(seen[l]);
                bytes += seen[l];
                bits += seen[l] * codeBits[l];
                repeats += Math.max(0, seen[l] - 1) * codeBits[l];
            }
        }

        // -- Painting ----------------------------------------------------
        void paint(Graphics2D g) {
            double reached = speed * t;                // how far the beam's head has got
            double released = speed * (t - cut);       // ...and, once the input has run out, its tail
            int root = tree.root();
            for (int n = 0; n < share.length; n++) {
                boolean feed = n == root;              // the line out of the cannon is always a beam, as big as the input
                if (share[n] < 0.004 && !feed) {
                    continue;
                }
                double to = Math.min(1, Math.max(0, (reached - start[n]) / len[n]));
                double from = cut < 0 ? 0 : Math.min(1, Math.max(0, (released - start[n]) / len[n]));
                if (to <= from) {
                    continue;
                }
                corners(n);
                Path2D.Double line = new Path2D.Double();
                trace(line, from, to);
                float w = (float) Math.max(1.5, ((feed ? 3 : 1.5) + 8 * Math.sqrt(share[n])) * power);
                Color hue = Theme.mix(Theme.ACCENT, Theme.VIOLET, 0.6 * Math.min(1, start[n] / reach));   // blue at the cannon, turning violet deeper in the tree
                float breathe = (float) (1 + 0.08 * Math.sin(t * 22 + start[n] * 0.05));
                g.setStroke(round(w * 4.2f * breathe));   // light round the beam, from wide and faint to a white-hot core
                g.setColor(fade(hue, 0.07));
                g.draw(line);
                g.setStroke(round(w * 2.6f * breathe));
                g.setColor(fade(hue, 0.16));
                g.draw(line);
                g.setStroke(round(w * 1.5f));
                g.setColor(fade(hue, 0.6));
                g.draw(line);
                g.setStroke(round(w * 0.9f));
                g.setColor(fade(Theme.ACCENT_HOVER, 0.9));
                g.draw(line);
                g.setStroke(round(w * 0.35f));
                g.setColor(fade(Color.WHITE, 0.95));
                g.draw(line);
                float gap = w * 1.6f + 9;   // beads of light running down the beam
                g.setColor(fade(Color.WHITE, 0.92));
                g.setStroke(new BasicStroke(w * 0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {0.1f, gap}, (float) (gap - (t * 140) % gap)));
                g.draw(line);
                if (to < 1 && from == 0) {   // the head of the beam: a bright bead in a halo, with a flare
                    pointAt(to);
                    double r = w * 1.4 + 3;
                    g.setPaint(new RadialGradientPaint((float) pt[0], (float) pt[1], (float) (r * 3.2), new float[] {0f, 0.4f, 1f},
                        new Color[] {fade(Color.WHITE, 0.9), fade(hue, 0.35), fade(hue, 0)}));
                    g.fill(dot(r * 3.2));
                    g.setColor(fade(Theme.ACCENT_HOVER, 0.9));
                    g.fill(dot(r));
                    g.setColor(Color.WHITE);
                    g.fill(dot(r * 0.5));
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.setColor(fade(Color.WHITE, 0.55 * (0.85 + 0.15 * Math.sin(t * 40))));
                    g.draw(new Line2D.Double(pt[0] - r * 2.4, pt[1], pt[0] + r * 2.4, pt[1]));
                    g.draw(new Line2D.Double(pt[0], pt[1] - r * 2.4, pt[0], pt[1] + r * 2.4));
                }
            }
            double small = 0.6 + 0.4 * power;   // an electron of a small input is smaller
            g.setStroke(new BasicStroke(1f));
            for (Electron e : flying) {
                int n = path[e.leaf][e.k];
                corners(n);
                Color hue = Theme.mix(Theme.ACCENT_HOVER, Theme.VIOLET, 0.8 * Math.min(1, (start[n] + e.s) / reach));
                for (int tail = 5; tail >= 1; tail--) {   // a trail that fades behind it
                    pointAt(Math.max(0, (e.s - tail * 5.0) / len[n]));
                    g.setColor(fade(hue, 0.5 * (1 - tail / 6.0)));
                    g.fill(dot(3.2 * small * (1 - tail / 7.0)));
                }
                pointAt(e.s / len[n]);
                g.setColor(fade(Theme.VIOLET, 0.3));
                g.fill(dot(8 * small));
                g.setColor(fade(hue, 0.9));
                g.fill(dot(3.8 * small));
                g.setColor(Color.WHITE);
                g.fill(dot(1.8 * small));
            }
            for (double[] hit : hits) {   // where an electron lands: a glow, a ring and a few sparks
                corners((int) hit[0]);
                double u = hit[1] / 0.4;
                double r = (3 + 14 * u) * small;
                pt[0] = rx[3];
                pt[1] = ry[3];
                g.setPaint(new RadialGradientPaint((float) pt[0], (float) pt[1], (float) (r * 1.8), new float[] {0f, 1f},
                    new Color[] {fade(Theme.ACCENT_HOVER, 0.5 * (1 - u)), fade(Theme.ACCENT_HOVER, 0)}));
                g.fill(dot(r * 1.8));
                g.setColor(fade(Color.WHITE, 0.8 * (1 - u)));
                g.setStroke(new BasicStroke((float) (2 * (1 - u) + 0.5)));
                g.draw(new Ellipse2D.Double(rx[3] - r, ry[3] - r, 2 * r, 2 * r));
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(fade(Color.WHITE, 0.7 * (1 - u)));
                for (int k = 0; k < 6; k++) {
                    double a = k * Math.PI / 3 + hit[0];
                    g.draw(new Line2D.Double(rx[3] + Math.cos(a) * r * 0.7, ry[3] + Math.sin(a) * r * 0.7, rx[3] + Math.cos(a) * r * 1.5, ry[3] + Math.sin(a) * r * 1.5));
                }
            }
            g.setStroke(new BasicStroke(1f));
        }

        private BasicStroke round(float width) {
            return new BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
        }

        private Ellipse2D.Double dot(double r) {
            dot.setFrame(pt[0] - r, pt[1] - r, 2 * r, 2 * r);
            return dot;
        }

        /** The corners of the line into node {@code n}, in {@link #rx} and {@link #ry}: the same as the line drawn there, or for the root straight down from the cannon. */
        private void corners(int n) {
            if (parent[n] >= 0) {
                route(node[parent[n]], node[n], rx, ry);
                return;
            }
            Cube root = node[n];
            double rs = root.size * zoom;
            rx[0] = rx[1] = rx[2] = rx[3] = panX + root.x * zoom - rs * 0.07;
            ry[3] = panY + root.y * zoomY - rs / 2;
            ry[0] = ry[3] - FEED_PX;
            ry[1] = ry[2] = (ry[0] + ry[3]) / 2;
        }

        private double segment(int i) {
            return Math.hypot(rx[i + 1] - rx[i], ry[i + 1] - ry[i]);
        }

        /** Adds to {@code out} the part of the line in rx, ry from {@code from} to {@code to} (0 to 1 of its length). */
        private void trace(Path2D.Double out, double from, double to) {
            double all = segment(0) + segment(1) + segment(2);
            double a = from * all;
            double b = to * all;
            double at = 0;
            boolean open = false;
            for (int i = 0; i < 3; i++) {
                double seg = segment(i);
                double lo = Math.max(a, at);
                double hi = Math.min(b, at + seg);
                if (hi > lo) {
                    double f0 = (lo - at) / seg;
                    double f1 = (hi - at) / seg;
                    if (!open) {
                        out.moveTo(rx[i] + (rx[i + 1] - rx[i]) * f0, ry[i] + (ry[i + 1] - ry[i]) * f0);
                        open = true;
                    }
                    out.lineTo(rx[i] + (rx[i + 1] - rx[i]) * f1, ry[i] + (ry[i + 1] - ry[i]) * f1);
                }
                at += seg;
            }
        }

        /** Puts the point {@code u} (0 to 1) of the way along the line in rx, ry into {@link #pt}. */
        private void pointAt(double u) {
            double at = u * (segment(0) + segment(1) + segment(2));
            for (int i = 0; i < 3; i++) {
                double seg = segment(i);
                if (at <= seg || i == 2) {
                    double f = seg == 0 ? 0 : Math.min(1, at / seg);
                    pt[0] = rx[i] + (rx[i + 1] - rx[i]) * f;
                    pt[1] = ry[i] + (ry[i + 1] - ry[i]) * f;
                    return;
                }
                at -= seg;
            }
        }
    }

    // -- Animation -----------------------------------------------------
    /** Moves everything on by {@code seconds}. Returns false once nothing is moving any more. */
    boolean tick(double seconds) {
        layoutIfNeeded();
        clock += seconds;
        blast = Math.max(0, blast - seconds / 0.6);
        spin += seconds * (2 + 6 * charge);
        swirl += seconds * swirlRate();
        absorb += ((charging ? 1 : 0) - absorb) * (1 - Math.exp(-seconds / 0.25));
        // The claw closes in on the core as it charges, and is thrown open by the shot; it opens quicker than it closes.
        double open = beam != null ? 1 : 0.85 - 0.85 * charge;
        claw += (open - claw) * (1 - Math.exp(-seconds / (open > claw ? 0.05 : 0.3)));
        boolean moving = false;
        double k = 1 - Math.exp(-seconds / glide);
        for (Cube c : cubes) {
            if (Double.isNaN(c.x)) {
                continue;
            }
            c.x += (c.tx - c.x) * k;
            c.y += (c.ty - c.y) * k;
            c.size += (c.tsize - c.size) * k;
            if (Math.abs(c.tx - c.x) < 0.3 && Math.abs(c.ty - c.y) < 0.3 && Math.abs(c.tsize - c.size) < 0.2) {
                c.x = c.tx;
                c.y = c.ty;
                c.size = c.tsize;
            } else {
                moving = true;
            }
            c.age += seconds;
            c.flash = c.selected ? 1 : Math.max(0, c.flash - seconds / FLASH);
            c.fill = Math.abs(c.fillTo - c.fill) < 0.002 ? c.fillTo : c.fill + (c.fillTo - c.fill) * (1 - Math.exp(-seconds / 0.15));
            moving |= c.age < POP + GROW || c.flash > 0 || c.fill != c.fillTo;
        }
        if (target != null) {
            fly(seconds);
            moving = true;
        }
        repaint();
        return moving;
    }

    private void layoutIfNeeded() {
        if (dirty || getWidth() != laidOutWidth || getHeight() != laidOutHeight) {
            arrange();
        }
    }

    private void arrange() {
        laidOutWidth = getWidth();
        laidOutHeight = getHeight();
        dirty = false;
        if (laidOutWidth == 0) {
            return;
        }
        if (tree == null) {
            arrangeGrid();
        } else {
            fitCamera();
            layoutForest();
        }
        snap = false;
    }

    /** Works out the biggest cubes that all fit, and where each one goes. */
    private void arrangeGrid() {
        int n = cubes.size();
        if (n == 0) {
            return;
        }
        int left = 16;
        int top = 40;
        int areaW = getWidth() - 2 * left;
        int areaH = getHeight() - top - 16;
        int s = MAX_SIZE + 2;
        int gap;
        int cols;
        int rows;
        do {
            s -= 2;
            gap = Math.max(4, s / 6);
            cols = Math.max(1, (areaW + gap) / (s + gap));
            rows = (n + cols - 1) / cols;
        } while (rows * (s + gap) - gap > areaH && s > MIN_SIZE);
        int y0 = top + (areaH - (rows * (s + gap) - gap)) / 2;
        for (int i = 0; i < n; i++) {
            Cube c = cubes.get(i);
            int row = i / cols;
            int inRow = Math.min(cols, n - row * cols);   // the last row may be short; it is centered
            int x0 = left + (areaW - (inRow * (s + gap) - gap)) / 2;
            c.tx = x0 + (i % cols) * (s + gap) + s / 2.0;
            c.ty = y0 + row * (s + gap) + s / 2.0;
            c.tsize = s;
            if (Double.isNaN(c.x) || snap) {
                c.x = c.tx;
                c.y = c.ty;
                c.size = s;
            }
        }
    }

    /**
     * Sets the camera once for the whole build, so that the finished tree just fits: how big
     * the nodes are, how far apart the levels are, and where the tree's origin (the middle of
     * the leaf in the first slot) is on the screen. A tree too wide for the stage gets small
     * nodes, but its levels are then spread out to use the height.
     */
    private void fitCamera() {
        if (getWidth() == 0) {
            return;
        }
        int levels = tree.height(tree.root());
        double sceneW = tree.leafCount() * SLOT;
        double w = getWidth() - 2 * EDGE;
        double h = getHeight() - TOP - BOTTOM - reserve;
        baseZoom = Math.min(MAX_ZOOM, Math.min(w / sceneW, h / (levels * LEVEL + NODE)));
        baseZoomY = levels == 0 ? baseZoom : Math.max(baseZoom, Math.min(MAX_ZOOM, (h - NODE * baseZoom) / (levels * LEVEL)));
        view = 1;
        zoom = baseZoom;
        zoomY = baseZoomY;
        double height = levels * LEVEL * zoomY + NODE * zoom;   // from the top of the root to the bottom of the leaves
        panX = EDGE + (w - sceneW * zoom) / 2;
        panY = TOP + reserve + (h - height) / 2 + levels * LEVEL * zoomY + NODE * zoom / 2;   // the leaves' row, the lowest
    }

    // -- Zoom and drag -------------------------------------------------
    /** Switches the zoom control and the dragging on, once the tree is finished. */
    void setInteractive(boolean on) {
        interactive = on;
        repaint();
    }

    /** Told each time the tree is dragged, for whatever else has to move with it. */
    void setPanListener(Runnable listener) {
        panListener = listener;
    }

    /** How far the tree has been dragged, which is how far the dotted background has to move. */
    Point dotShift() {
        return new Point((int) Math.round(dotX), (int) Math.round(dotY));
    }

    /** How far in the zoom goes: the nodes stop at MAX_CUBE pixels. */
    private double maxView() {
        return Math.max(1, MAX_CUBE / (NODE * baseZoom));
    }

    /**
     * Sets zoom and zoomY for the current view. A wide tree has its levels spread over the stage;
     * the closer in, the less, until at full zoom they are at their natural distance.
     */
    private void scale() {
        double p = Math.log(view) / Math.log(maxView());
        zoom = baseZoom * view;
        zoomY = zoom * Math.pow(baseZoomY / baseZoom, 1 - p);
    }

    /** Zooms to {@code v}, within its limits, keeping the point of the tree at ({@code ax}, {@code ay}) where it is. */
    private void setView(double v, double ax, double ay) {
        double x = (ax - panX) / zoom;
        double y = (ay - panY) / zoomY;
        view = Math.max(1, Math.min(maxView(), v));
        scale();
        panX = ax - x * zoom;
        panY = ay - y * zoomY;
        clampPan();
        repaint();
        zoomBar.repaint();
    }

    /** Zooms to {@code p} (0 to 1) of the way along the control's track, about the middle of the stage, on the log scale the track has. */
    private void zoomTo(double p) {
        setView(Math.exp(p * Math.log(maxView())), getWidth() / 2.0, (TOP + getHeight() - BOTTOM) / 2.0);
    }

    /** Whether the person can zoom and drag: the tree is finished, and the camera is not on its way somewhere. */
    private boolean free() {
        return interactive && target == null;
    }

    /**
     * Sends the camera flying, in a moment, to the cube of byte {@code symbol}: as close in as the
     * zoom goes, with the cube in the middle of the stage (or as near as the tree lets it be).
     * Until it gets there nothing can zoom, drag or send it anywhere else.
     *
     * @return whether it set off: not while the tree is unfinished, or the camera is already flying
     */
    boolean flyTo(int symbol) {
        if (!free()) {
            return false;
        }
        target = bySymbol[symbol];
        flown = 0;
        fromX = panX + target.x * zoom;
        fromY = panY + target.y * zoomY;
        fromView = view;
        return true;
    }

    /** One step of the flight: the zoom goes in, about the cube so that it stays where it is, and the cube slides to the middle. */
    private void fly(double seconds) {
        flown = Math.min(1, flown + seconds / FLY);
        double s = flown * flown * (3 - 2 * flown);
        setView(fromView * Math.pow(maxView() / fromView, s), panX + target.x * zoom, panY + target.y * zoomY);
        pan(fromX + (getWidth() / 2.0 - fromX) * s - (panX + target.x * zoom),
            fromY + ((TOP + getHeight() - BOTTOM) / 2.0 - fromY) * s - (panY + target.y * zoomY));
        if (flown == 1) {
            target = null;
        }
    }

    private void pan(double dx, double dy) {
        double x = panX;
        double y = panY;
        panX += dx;
        panY += dy;
        clampPan();
        dotX += panX - x;   // the dots follow by what the tree really moved, not by what the pointer did
        dotY += panY - y;
        panListener.run();
    }

    /** Keeps the tree in view: it always covers the stage or, when it is smaller, sits in the middle of it. */
    private void clampPan() {
        double w = tree.leafCount() * SLOT * zoom;
        double h = tree.height(tree.root()) * LEVEL * zoomY + NODE * zoom;
        double half = NODE * zoom / 2;   // the leaves' row is panY, so this much of it is below
        panX = within(panX, w, EDGE, getWidth() - EDGE);
        panY = within(panY - h + half, h, TOP, getHeight() - BOTTOM) + h - half;
    }

    /** Where something {@code size} long, now at {@code start}, can be so that it covers from..to; if it is too short to, in the middle. */
    private static double within(double start, double size, double from, double to) {
        return size <= to - from ? from + (to - from - size) / 2 : Math.max(to - size, Math.min(from, start));
    }

    private double slot;   // the next free leaf slot, while laying out the forest

    /** Gives every node of every tree in the queue its place: leaves in a row, each node over its two. */
    private void layoutForest() {
        slot = 0;
        for (Cube root : forest) {
            place(root);
        }
    }

    private void place(Cube c) {
        if (c.left == null) {
            c.tx = slot + SLOT / 2;
            c.ty = 0;
            slot += SLOT;
        } else {
            place(c.left);
            place(c.right);
            c.tx = (c.left.tx + c.right.tx) / 2;
            c.ty = -c.height * LEVEL;
        }
        c.tsize = NODE;
        if (Double.isNaN(c.x) || snap) {
            c.x = c.tx;
            c.y = c.ty;
            c.size = NODE;
        }
    }

    // -- Painting ------------------------------------------------------
    @Override
    protected void paintComponent(Graphics g) {
        layoutIfNeeded();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        g2.setFont(Theme.FONT_LABEL);
        g2.setColor(Theme.TEXT_MUTED);
        g2.drawString(caption, 16, 26);

        if (message != null) {
            g2.setColor(messageIsError ? Theme.ERROR : Theme.TEXT_MUTED);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(message, (getWidth() - fm.stringWidth(message)) / 2, getHeight() / 2);
        }

        Graphics2D stage = (Graphics2D) g2.create();
        stage.clipRect(0, TOP - 8, getWidth(), getHeight());   // a zoomed tree stays out from under the caption
        if (tree != null) {
            paintLines(stage);
            if (beam != null) {
                beam.paint(stage);
            }
        }
        for (Cube c : cubes) {
            paintCube(stage, c);
        }
        if (cannonShow > 0) {
            paintMuzzle(stage);
        }
        stage.dispose();
        g2.dispose();
    }

    /** The zoom control, for whoever puts it on the screen; it only needs to be given room for its preferred size. */
    JPanel zoomBar() {
        return zoomBar;
    }

    /** The zoom control: a box like the others, with an accent edge, a track of dots and a spiky thumb like the buttons. */
    @SuppressWarnings("serial")
    private final class ZoomBar extends JPanel {

        private boolean sliding;   // the thumb is held
        private boolean hover;     // the pointer is on the track

        ZoomBar() {
            setOpaque(false);
            setPreferredSize(new Dimension(BAR_W, BAR_H));
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (free() && SwingUtilities.isLeftMouseButton(e) && track().contains(e.getPoint())) {
                        sliding = true;
                        moveThumb(e.getX());
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (sliding) {
                        moveThumb(e.getX());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    sliding = false;
                    mouseMoved(e);
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    boolean over = free() && track().contains(e.getPoint());
                    setCursor(over ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                    if (over != hover) {
                        hover = over;
                        repaint();
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    if (hover && !sliding) {
                        hover = false;
                        repaint();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        /** What can be pressed to move the thumb: between the title and the number. */
        private Rectangle track() {
            return new Rectangle(58, 3, 112, BAR_H - 3);
        }

        private void moveThumb(int x) {
            Rectangle t = track();
            zoomTo(Math.max(0, Math.min(1, (x - t.x - THUMB / 2.0) / (t.width - THUMB))));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Rectangle t = track();
            g2.setColor(fade(Theme.EDITOR_BG, 0.94));
            g2.fillRect(0, 0, BAR_W, BAR_H);
            Theme.frame(g2, 0, 0, BAR_W, BAR_H, 8);

            int cy = 3 + (BAR_H - 3) / 2;
            g2.setFont(Theme.FONT_TITLE);
            FontMetrics fm = g2.getFontMetrics();
            int base = cy + (fm.getAscent() - fm.getDescent()) / 2;
            g2.setColor(Theme.TEXT_PRIMARY);
            g2.drawString("ZOOM", 12, base);
            String value = String.format("%.1fx", view);
            g2.setColor(Theme.VIOLET);
            g2.drawString(value, BAR_W - 10 - fm.stringWidth(value), base);

            int x0 = t.x + THUMB / 2;
            int x1 = t.x + t.width - THUMB / 2;
            int thumb = (int) Math.round(x0 + (x1 - x0) * Math.log(view) / Math.log(maxView()));
            for (int x = x0; x <= x1; x += 6) {
                g2.setColor(x <= thumb ? Theme.ACCENT : Theme.TEXT_MUTED);
                g2.fillRect(x - 1, cy - 1, 2, 2);
            }
            g2.setColor(sliding ? Theme.ACCENT_PRESSED : hover ? Theme.ACCENT_HOVER : Theme.ACCENT);
            g2.fillPolygon(new int[] {thumb - 12, thumb - 5, thumb + 5, thumb + 12, thumb + 5, thumb - 5},
                new int[] {cy, cy - 7, cy - 7, cy, cy + 7, cy + 7}, 6);
            g2.dispose();
        }
    }

    /** The lines from each joining node to the two under it, with the 0 or 1 each stands for. */
    private void paintLines(Graphics2D g2) {
        g2.setStroke(new BasicStroke((float) Math.max(1.2, Math.min(3, NODE * zoom * 0.06)), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (Cube p : cubes) {
            if (p.left != null && !Double.isNaN(p.x)) {
                line(g2, p, p.left, "0");
                line(g2, p, p.right, "1");
            }
        }
        g2.setStroke(new BasicStroke(1f));
    }

    /** The corners of the line from {@code p} down to {@code c}: out of the middle of the parent's front (a little left of its middle), across, into the child's top. */
    private void route(Cube p, Cube c, double[] xs, double[] ys) {
        double ps = p.size * zoom;
        double cs = c.size * zoom;
        xs[0] = xs[1] = panX + p.x * zoom - ps * 0.07;
        xs[2] = xs[3] = panX + c.x * zoom - cs * 0.07;
        ys[0] = panY + p.y * zoomY + ps / 2;
        ys[1] = ys[2] = panY + (p.y + LEVEL / 2) * zoomY;
        ys[3] = panY + c.y * zoomY - cs / 2;
    }

    /**
     * One line, bent like a fork: down from the parent to halfway to the next row, across to the
     * child's column, down to the child. Every parent forks in its own gap between two rows, and
     * the drop to a child is in the child's own column, which has nothing else in it (the nodes
     * above a child are only its ancestors), so a line can never cross another line or a cube.
     */
    private void line(Graphics2D g2, Cube p, Cube c, String bit) {
        double grow = Math.min(1, p.age / GROW);
        if (grow <= 0) {
            return;
        }
        double ps = p.size * zoom;
        double[] xs = new double[4];
        double[] ys = new double[4];
        route(p, c, xs, ys);
        double x1 = xs[0];
        double x2 = xs[3];
        double y1 = ys[0];
        double yb = ys[1];
        double y2 = ys[3];
        Color main = Theme.mix(Theme.ACCENT, Theme.TEXT_MUTED, Math.min(1, p.age / 0.9));
        Stroke thin = g2.getStroke();
        Stroke wide = new BasicStroke(((BasicStroke) thin).getLineWidth() * 3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);   // a soft glow under the line
        double left = grow * (Math.abs(yb - y1) + Math.abs(x2 - x1) + Math.abs(y2 - yb));   // how much of the line is drawn so far
        for (int i = 0; i < 3 && left > 0; i++) {
            double len = Math.abs(xs[i + 1] - xs[i]) + Math.abs(ys[i + 1] - ys[i]);
            double f = len == 0 ? 1 : Math.min(1, left / len);
            Line2D.Double part = new Line2D.Double(xs[i], ys[i], xs[i] + (xs[i + 1] - xs[i]) * f, ys[i] + (ys[i + 1] - ys[i]) * f);
            g2.setStroke(wide);
            g2.setColor(fade(Theme.ACCENT, 0.1));
            g2.draw(part);
            g2.setStroke(thin);
            g2.setColor(main);
            g2.draw(part);
            left -= len;
        }
        double r = Math.max(7, Math.min(15, ps * 0.17));   // the 0 or 1 sits on the way across, if there is room for it
        if (grow >= 1 && Math.abs(x2 - x1) >= 2 * r + 6) {
            double mx = (x1 + x2) / 2;
            g2.setColor(Theme.CHROME_BG);
            g2.fill(new Ellipse2D.Double(mx - r, yb - r, 2 * r, 2 * r));
            g2.setFont(BIT.deriveFont((float) (r * 1.3)));
            g2.setColor(Theme.VIOLET);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(bit, (float) (mx - fm.stringWidth(bit) / 2.0), (float) (yb + (fm.getAscent() - fm.getDescent()) / 2.0));
        }
    }

    private void fonts(int s) {
        if (s == fontSize) {
            return;
        }
        fontSize = s;
        big = Theme.FONT_EDITOR.deriveFont(Font.BOLD, Math.max(10f, s * 0.4f));
        small = Theme.FONT_EDITOR.deriveFont(Font.BOLD, Math.max(9f, s * 0.27f));
        tiny = Theme.FONT_LABEL.deriveFont(Math.max(8f, s * 0.22f));
        num = Theme.FONT_EDITOR.deriveFont(Font.BOLD, Math.max(10f, s * 0.3f));
    }

    /**
     * A cube seen slightly from above and the right: a square front, a top and a side. A byte's
     * front has the byte on top of a plate with its count. A joining node is violet and shows
     * the count on its whole front. When the cubes get small the plate goes, then the text, then
     * the top and side, which shrink into a flat square: all of it fades or grows with the size,
     * so that zooming in or out never jumps.
     */
    private void paintCube(Graphics2D g, Cube c) {
        if (Double.isNaN(c.x)) {
            return;
        }
        double pop = c.age >= POP ? 1 : easeOutBack(c.age / POP);
        if (pop < 0.02) {
            return;
        }
        int s = Math.max(1, (int) Math.round(c.size * zoom));
        boolean joined = c.symbol < 0;
        double depth = ramp(s, FLAT, CUBE);   // 0: a flat square, 1: a whole cube
        double text = ramp(s, 13, 19);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.translate(panX + c.x * zoom, panY + c.y * zoomY);
        g2.scale(pop, pop);
        g2.translate(-s / 2.0, -s / 2.0);
        if (s >= 13) {
            fonts(s);
        }
        int d = (int) Math.round(Math.max(3, s * 14 / 100) * depth);   // how far the top and side reach
        int w = s - d;                       // the front is w by w
        int plate = joined ? 0 : (int) Math.round((s < 24 ? 8 : Math.max(11, w * 40 / 100)) * ramp(s, 16, 22));   // the strip along the bottom that holds the count
        int body = w - plate;                // the rest of the front

        int shadow = Math.max(2, d / 2);
        g2.setColor(fade(Color.BLACK, 0.27 * depth));   // shadow on the stage
        g2.fillRect(shadow, d + shadow + 1, s, w);

        // Flat, the whole square is the color of a cube's top, so that it shows up; it darkens into the front.
        g2.setColor(Theme.mix(Theme.mix(joined ? JOINED_TOP : TOP_LOW, joined ? JOINED_FRONT : FRONT, depth), Theme.CARET, c.flash * (1 - depth)));
        g2.fillRect(0, d, w, w);
        if (plate > 0) {
            g2.setColor(PLATE);
            g2.fillRect(1, d + body, w - 1, plate - 1);
        }
        if (c.fill > 0.004 && !joined) {   // what the beam has delivered, rising from the bottom of the front
            int level = Math.max(1, (int) Math.round(w * c.fill));
            g2.setColor(fade(Theme.ACCENT, 0.5));
            g2.fillRect(0, d + w - level, w, level);
            g2.setColor(fade(Theme.ACCENT_HOVER, 0.9));
            g2.fillRect(0, d + w - level, w, 1);
        }

        Polygon top = new Polygon(new int[] {0, d, s, w}, new int[] {d, 0, 0, d}, 4);
        Polygon side = new Polygon(new int[] {w, s, s, w}, new int[] {d, 0, w, s}, 4);
        if (d > 0) {
            g2.setColor(Theme.mix(joined ? JOINED_TOP : TOP_LOW, Theme.CARET, c.flash));
            g2.fillPolygon(top);
            g2.setColor(joined ? JOINED_SIDE : SIDE_LOW);
            g2.fillPolygon(side);
        }

        g2.setColor(fade(Theme.DIVIDER, depth));
        g2.drawRect(0, d, w, w);
        if (plate > 0) {
            g2.drawLine(0, d + body, w, d + body);
        }
        if (d > 0) {
            g2.drawPolygon(top);
            g2.drawPolygon(side);
        }
        if (c.flash > 0.02 && d > 0) {
            g2.setColor(fade(Theme.CARET, 0.9 * c.flash * depth));
            g2.setStroke(new BasicStroke(c.selected ? 3f : 2f));
            g2.drawPolygon(new int[] {0, d, s, s, w, 0}, new int[] {d, 0, 0, w, s, s}, 6);
        }

        if (text <= 0) {
            g2.dispose();
            return;
        }
        g2.setComposite(AlphaComposite.SrcOver.derive((float) text));
        if (joined) {
            drawCount(g2, c.count, w, d, w, num);
            g2.dispose();
            return;
        }
        String label = label(c.symbol);
        g2.setFont(label.length() == 1 ? big : small);
        FontMetrics fm = g2.getFontMetrics();
        if (fm.stringWidth(label) <= w - 2) {
            g2.setColor(isSpecial(c.symbol) ? Theme.VIOLET : Theme.TEXT_PRIMARY);
            g2.drawString(label, (w - fm.stringWidth(label)) / 2, d + body / 2 + (fm.getAscent() - fm.getDescent()) / 2);
        }
        if (plate >= 6) {
            g2.setComposite(AlphaComposite.SrcOver.derive((float) Math.min(text, ramp(plate, 5, 8))));
            drawCount(g2, c.count, w, d + body, plate, tiny);
        }
        g2.dispose();
    }

    /** 0 up to {@code from}, 1 from {@code to}, and a smooth ease between. */
    private static double ramp(double v, double from, double to) {
        double t = Math.max(0, Math.min(1, (v - from) / (to - from)));
        return t * t * (3 - 2 * t);
    }

    private static Color fade(Color c, double alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) Math.round(255 * Math.max(0, Math.min(1, alpha))));
    }

    /** The count, centered in the box at {@code y}: the exact number when it fits, else a short form, shrunk if need be. */
    private static void drawCount(Graphics2D g2, int count, int width, int y, int height, Font font) {
        String text = String.format("%,d", count);
        g2.setFont(font);
        if (g2.getFontMetrics().stringWidth(text) > width - 4) {
            text = compact(count);
        }
        for (float size = font.getSize2D(); size > 7 && (g2.getFontMetrics().stringWidth(text) > width - 2 || g2.getFontMetrics().getHeight() > height + 1); size--) {
            g2.setFont(font.deriveFont(size - 1));
        }
        g2.setColor(Theme.TEXT_PRIMARY);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(text, (width - fm.stringWidth(text)) / 2, y + (height - fm.getHeight()) / 2 + fm.getAscent());
    }

    private static double easeOutBack(double t) {
        double c1 = 1.70158;
        double c3 = c1 + 1;
        return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
    }

    // -- Labels --------------------------------------------------------
    /** What a cube shows for a byte: the character, or a short name when it has no visible shape. */
    static String label(int symbol) {
        return switch (symbol) {
            case 0x20 -> "SP";
            case '\n' -> "LF";
            case '\r' -> "CR";
            case '\t' -> "TAB";
            default -> symbol > 0x20 && symbol < 0x7F ? String.valueOf((char) symbol) : String.format("%02X", symbol);
        };
    }

    /** Whether {@link #label(int)} is a name rather than the character itself. */
    static boolean isSpecial(int symbol) {
        return symbol <= 0x20 || symbol >= 0x7F;
    }

    /** 12,345 becomes 12k: short enough for a small cube. */
    private static String compact(int count) {
        if (count < 10_000) {
            return String.valueOf(count);
        }
        if (count < 1_000_000) {
            return count / 1000 + "k";
        }
        return count / 1_000_000 + "M";
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        if (e.getY() < TOP - 8) {
            return null;   // under the caption
        }
        for (int i = cubes.size() - 1; i >= 0; i--) {
            Cube c = cubes.get(i);
            double half = c.size * zoom / 2;
            if (Math.abs(e.getX() - (panX + c.x * zoom)) <= half && Math.abs(e.getY() - (panY + c.y * zoomY)) <= half) {
                return c.symbol < 0 ? String.format("%,d (two nodes joined)", c.count)
                    : String.format("%s (0x%02X): %,d time%s", label(c.symbol), c.symbol, c.count, c.count == 1 ? "" : "s");
            }
        }
        return null;
    }
}
