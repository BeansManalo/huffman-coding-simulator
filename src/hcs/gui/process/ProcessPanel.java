package hcs.gui.process;

import hcs.core.FrequencyScan;
import hcs.core.FrequencyScan.Checkpoint;
import hcs.core.HuffmanTree;
import hcs.gui.MainFrame;
import hcs.gui.Theme;
import hcs.gui.dialog.ConfirmDialog;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLayer;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.plaf.LayerUI;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * The process screen: the cubes of the byte frequencies, which become the Huffman tree, in the
 * middle, and the table of Huffman codes beside them.
 * <p>
 * Instant, the tree and the table are simply there. Animated, a "reading" package on top shows
 * the part of the input being read while the cubes appear in real time; then the package folds
 * away, the table package is revealed, the cubes line up and the tree is built one merge at a
 * time, and a spirit of each cube flies to its row of the table with its code; a row, and its
 * line, are there only once its spirit has landed. Then three cards (the size of the input, the
 * size of the symbols and codes, and how much was saved) sweep in showing 0. A cannon comes in over the root
 * and charges: each row of the table clones itself, the clone breaks into the 0s and 1s of its
 * symbol (8 bits: a table that did not say what its codes are of would mean nothing) and of its
 * code, and these fly to the cannon and enter orbit round its core, each digit becoming one mote
 * of the energy swirling there, which spirals in. The charge, and the compressed size card, which
 * counts up with it, grow as each mote is absorbed: the table accounts for each symbol once and one copy of each code, no more. After a moment the cannon replays the input down
 * the tree as a beam and electrons; the card adds every further copy as these reach the cubes,
 * and the other two cards, and the cubes' fill, move only as these reach the cubes. Last the
 * cannon goes, and the zoom control and the BACK button sweep in. Until then the show cannot be
 * left, and the table cannot be touched; after it, a click on a row of the table sends the
 * camera flying to the cube of its byte.
 * <p>
 * Meanwhile SKIP, in BACK's place, offers to end the show at once. The show is paused while the
 * question is open; on YES the owner swaps this screen for the finished one. SKIP sweeps out as BACK sweeps in.
 */
@SuppressWarnings("serial")
public final class ProcessPanel extends JPanel {

    // Reading takes 40 ms a byte, but never under 1.8 s or over 8 s: a huge file is replayed
    // as quickly as a medium one (about 200 bytes) is.
    private static final double MS_PER_BYTE = 40;
    private static final double MIN_READ_MS = 1800;
    private static final double MAX_READ_MS = 8000;
    private static final double HOLD_MS = 450;      // all read, before the cubes line up
    private static final double SETTLE_MS = 700;    // cubes line up, reading package folds away
    private static final double REVEAL_MS = 800;    // table package appears
    private static final double ROW_MS = 900;       // the cubes move to the row they are joined from
    // A merge takes 560 ms, but the whole build never takes over 9 s: a big tree is built
    // as fast as a medium one (about 16 merges).
    private static final double MERGE_MS = 560;
    private static final double MAX_TREE_MS = 9000;
    /** Where in a merge its three steps start: pick the two smallest, join them, slide the new tree into the row. */
    private static final double[] STEP = {0, 0.30, 0.62};
    private static final double PAUSE_MS = 600;     // the tree is done, before the spirits go
    // A spirit leaves every 110 ms, but all of them are away within 4.5 s.
    private static final double SPIRIT_GAP_MS = 110;
    private static final double SPIRIT_MAX_MS = 4500;
    // The three cards sweep in showing 0: each takes 0.7 s, 160 ms after the one before. Once they
    // have been seen at 0 for a moment the cannon comes in and charges: a clone leaves a row of the
    // table every 90 ms, though all are away within 8 s, and when the last mote has been taken in it
    // waits a moment and fires. The replay takes as long as the first reading did (so a big file is as quick as a
    // medium one). Then the cannon goes, and the slider sweeps in.
    private static final double STAT_MS = 700;
    private static final double STAT_GAP_MS = 160;
    private static final double CARDS_MS = 2 * STAT_GAP_MS + STAT_MS + 300;
    private static final double CANNON_MS = 800;    // the tree makes room above itself, the cannon sweeps in
    private static final double CLONE_GAP_MS = 90;
    private static final double CLONE_MAX_MS = 8000;
    private static final double CLONE_MIN_MS = 3000;   // the clones take at least this long to leave the table, so that a few digits are taken in one by one
    private static final double READY_MS = 600;     // charged, before it fires
    private static final double CLOSE_MS = 700;     // the cannon sweeps out, the tree has its room back
    private static final int CANNON_W = 340;
    private static final int STAT_HEIGHT = 64;
    private static final int STAT_SPACE = 10;
    private static final int TABLE_WIDTH = 360;
    private static final int ROW_HEIGHT = 28;
    private static final int GAP = 20;

    private enum Phase {
        WAIT, READ, HOLD, SETTLE, REVEAL, ROW, TREE, PAUSE, SPIRITS, CARDS, CANNON, CHARGE, READY, FIRE, DRAIN, CLOSE, ZOOM, DONE
    }

    private final FrequencyScan scan;
    private final boolean animated;
    private final CubeField cubes = new CubeField();
    private final ReadingView reading = new ReadingView();
    private final Reveal reveal = new Reveal(false, 0);
    private final Reveal zoomReveal = new Reveal(true, -60);
    private final JLayer<JPanel> zoomBox = new JLayer<>(cubes.zoomBar(), zoomReveal);
    private final Stat[] stats = {new Stat("UNCOMPRESSED SIZE", false), new Stat("COMPRESSED SIZE", false), new Stat("COMPRESSION RATIO", true)};
    private final double[] statValue = new double[3];   // what the cards count up to: the two sizes in bits, then the percent saved
    private final CodeModel model = new CodeModel();
    private final JTable table = codeTable(model);
    private final JScrollPane scroll = Theme.scroll(table);
    private final StageLayer spirits = new StageLayer(this::rowRect, this::landed, cubes::dotShift);
    private final JLayer<JPanel> stage;
    private final JComponent tableBox;
    private final JComponent shield = new JComponent() { };   // over the table until the show is over: takes the mouse, so the table does not
    private final Reveal backReveal = new Reveal(true, -60);   // BACK sweeps in with the zoom control
    private final Reveal skipReveal = new Reveal(true, -60);   // SKIP sweeps out as BACK sweeps in
    private JLayer<JPanel> backBox;
    private JButton btnBack;
    private JButton btnSkip;               // animated only
    private JLayer<JPanel> skipBox;
    private final JPanel topSlot;          // animated only: holds the reading package, folds away
    private ReadingView loader;            // animated only: the cannon's reader, which replays the input
    private Reveal cannonReveal;
    private JLayer<JPanel> cannon;         // ...and the cannon's box, over the root
    private final int boxHeight;
    private int slotHeight;
    private final Timer timer = new Timer(15, e -> tick());
    private Phase phase = Phase.WAIT;
    private boolean ready;
    private boolean stopped;
    private long last;
    private long phaseStart;
    private double readMs;
    private int shown = -1;                // the checkpoint on screen
    private HuffmanTree tree;
    private int[] order;                   // the bytes, most common first: the order of the table
    private double perMerge;               // milliseconds
    private int event;                     // the next step of the build, three to a merge
    private double spiritGap;              // milliseconds
    private int launched;
    private int landed;
    private double scrollY;
    private long tableBits;                // each symbol (8 bits) and one copy of its code: all the table can tell, and what the charge adds up to
    private long landedBits;               // ...and how many of them the cannon has taken in, as motes
    private int cloned;                    // the rows of the table cloned so far
    private double cloneGap;               // milliseconds

    /**
     * Create the panel, and start reading the input in the background (unless {@code scan} already
     * has). Nothing moves until {@link #start()}, so the panel can be put on the screen first.
     * {@code onSkip}, which animated panels call once SKIP is confirmed, takes over from there.
     */
    public ProcessPanel(FrequencyScan scan, boolean animated, Runnable onBack, Runnable onSkip) {
        this.scan = scan;
        this.animated = animated;
        setPreferredSize(MainFrame.WINDOW_SIZE);
        setBackground(Theme.CHROME_BG);
        setBorder(new EmptyBorder(24, 24, 24, 24));
        setLayout(new BorderLayout());

        scroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);   // a short table has no bar, so its rows go right to the edge
        JPanel corner = new JPanel();   // the square above the scroll bar, beside the table header
        corner.setBackground(Theme.EDITOR_BG);
        corner.setBorder(new MatteBorder(0, 0, 1, 0, Theme.DIVIDER));
        scroll.setCorner(JScrollPane.UPPER_RIGHT_CORNER, corner);
        // A JLayer lets the box be revealed in steps (see Reveal).
        tableBox = new JLayer<>(Theme.box("HUFFMAN TABLE", scroll), reveal);
        // The table package is half as tall as the stage, at the bottom of its side, with the cards just above it.
        JPanel side = new JPanel(null) {
            @Override
            public void doLayout() {
                int tableTop = getHeight() - getHeight() / 2;
                tableBox.setBounds(0, tableTop, getWidth(), getHeight() / 2);
                shield.setBounds(tableBox.getBounds());
                int top = tableTop - GAP - 3 * STAT_HEIGHT - 2 * STAT_SPACE;
                for (int i = 0; i < stats.length; i++) {
                    stats[i].card.setBounds(0, top + i * (STAT_HEIGHT + STAT_SPACE), getWidth(), STAT_HEIGHT);
                }
            }
        };
        side.setOpaque(false);
        side.setPreferredSize(new Dimension(TABLE_WIDTH, 0));
        side.add(tableBox);
        for (Stat stat : stats) {
            side.add(stat.card);
        }
        if (animated) {
            MouseAdapter block = new MouseAdapter() { };
            shield.addMouseListener(block);
            shield.addMouseMotionListener(block);
            shield.addMouseWheelListener(block);
            side.add(shield, 0);   // in front of the table
        }
        zoomReveal.progress = 0;   // the slider and the cards arrive when the table is done

        JPanel middle = new JPanel(new BorderLayout(24, 0));
        middle.setOpaque(false);
        middle.add(cubes, BorderLayout.CENTER);
        middle.add(side, BorderLayout.EAST);
        stage = new JLayer<>(middle, spirits);
        add(stage, BorderLayout.CENTER);
        spirits.onBits(this::coreAt, cubes::swirlRate,
            () -> cubes.setCharging(true),   // the energy starts to swirl with the first digit to reach the orbit
            () -> landedBits++);             // the charge is the motes the core has taken in: one for each digit
        cubes.setPanListener(this::repaint);   // the dotted background is the whole panel's, so it moves with the tree
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {   // a click on a row sends the camera flying to its byte, unless it is flying already
                int row = table.rowAtPoint(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && row >= 0 && model.isShown(row) && cubes.flyTo(order[row]) && !timer.isRunning()) {
                    last = System.nanoTime();   // the clock has been stopped since the show ended
                    timer.start();
                }
            }
        });

        btnBack = Theme.button("BACK");
        btnBack.addActionListener(e -> {
            if (ConfirmDialog.leave(this)) {
                stop();
                onBack.run();
            }
        });
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        actions.setBorder(new EmptyBorder(GAP, 0, 0, 0));
        JPanel zoomSlot = new JPanel(new GridBagLayout());   // keeps the slider its own size, in the middle of the row's height
        zoomSlot.setOpaque(false);
        zoomSlot.add(zoomBox);
        actions.add(zoomSlot, BorderLayout.WEST);
        backBox = new JLayer<>(slot(btnBack), backReveal);
        JPanel center = new JPanel(new GridBagLayout());   // BACK and SKIP share one place: SKIP while the show plays, BACK after
        center.setOpaque(false);
        GridBagConstraints same = new GridBagConstraints();
        same.gridx = 0;
        same.gridy = 0;
        center.add(backBox, same);   // on top, so that SKIP has the mouse while BACK is hidden
        actions.add(center, BorderLayout.CENTER);
        actions.add(Box.createHorizontalStrut(zoomBox.getPreferredSize().width), BorderLayout.EAST);   // BACK stays in the middle
        add(actions, BorderLayout.SOUTH);

        if (animated) {
            backReveal.progress = 0;   // the show cannot be left: BACK arrives when it is over
            showBack(false);
            btnSkip = Theme.ghost("SKIP");
            showSkip(false);   // until the input is read there is nothing to skip to
            btnSkip.addActionListener(e -> skip(onSkip));
            skipBox = new JLayer<>(slot(btnSkip), skipReveal);
            center.add(skipBox, same);   // under BACK
            reveal.progress = 0;   // the table package is made after the reading
            JPanel box = Theme.box("READING", reading);
            boxHeight = box.getPreferredSize().height;
            slotHeight = boxHeight + GAP;
            // The box keeps its size and slides up out of the slot, instead of being squashed.
            topSlot = new JPanel(null) {
                @Override
                public Dimension getPreferredSize() {
                    return new Dimension(0, slotHeight);
                }

                @Override
                public void doLayout() {
                    getComponent(0).setBounds(0, slotHeight - GAP - boxHeight, getWidth(), boxHeight);
                }
            };
            topSlot.setOpaque(false);
            topSlot.add(box);
            add(topSlot, BorderLayout.NORTH);

            loader = new ReadingView("Loading", 2);
            JPanel gun = Theme.box("BEAM CANNON", loader);
            cannonReveal = new Reveal(false, 0);
            cannonReveal.progress = 0;
            cannon = new JLayer<>(gun, cannonReveal);
            cannon.setVisible(false);
            cubes.setCannonSize(CANNON_W, gun.getPreferredSize().height);
            cubes.add(cannon);   // over the cubes, put in place by placeCannon()
        } else {
            boxHeight = 0;
            topSlot = null;
        }

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                if (scan.checkpoints() == null) {   // the finished screen of a skip gets a scan that is read already
                    scan.run();
                }
                return null;
            }

            @Override
            protected void done() {
                try {
                    get();
                    inputRead();
                } catch (ExecutionException e) {
                    fail("Could not read the file: " + e.getCause().getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }.execute();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Theme.backdrop(g, this);
        Point moved = cubes.dotShift();
        Theme.dots(g, this, moved.x, moved.y);   // the whole background is dotted, not just behind the cubes
    }

    // -- The table -----------------------------------------------------
    /** What the table shows: each byte and its code, most common first. A row can stay blank until its spirit lands. */
    private static final class CodeModel extends AbstractTableModel {

        private static final long FLASH_NS = 350_000_000L;
        private int[] symbols = new int[0];
        private String[] codes = new String[0];
        private boolean[] shown = new boolean[0];
        private long[] at = new long[0];   // when each row was shown, for its flash; 0 if it was there from the start

        void fill(HuffmanTree tree, boolean showNow) {
            symbols = tree.byFrequency();
            codes = new String[symbols.length];
            for (int i = 0; i < symbols.length; i++) {
                codes[i] = tree.code(symbols[i]);
            }
            shown = new boolean[symbols.length];
            Arrays.fill(shown, showNow);
            at = new long[symbols.length];
            fireTableDataChanged();
        }

        void show(int row) {
            shown[row] = true;
            at[row] = System.nanoTime();
            fireTableRowsUpdated(row, row);
        }

        boolean isShown(int row) {
            return shown[row];
        }

        /** 1 as a row has just been shown, down to 0 as its flash dies away. */
        double flash(int row) {
            return at[row] == 0 ? 0 : Math.max(0, 1 - (System.nanoTime() - at[row]) / (double) FLASH_NS);
        }

        String describe(int row) {
            return CubeField.label(symbols[row]) + String.format(" (0x%02X): ", symbols[row]) + codes[row];
        }

        @Override
        public int getRowCount() {
            return symbols.length;
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "SYMBOL" : "CODE";
        }

        @Override
        public Object getValueAt(int row, int column) {
            if (!shown[row]) {
                return null;
            }
            return column == 0 ? (Object) symbols[row] : codes[row];
        }
    }

    private static JTable codeTable(CodeModel model) {
        JTable table = new JTable(model) {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getRowCount() == 0 || !model.isShown(0)) {   // the spirits land in order, so row 0 is the first
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setFont(Theme.FONT_LABEL);
                    g2.setColor(Theme.TEXT_MUTED);
                    FontMetrics fm = g2.getFontMetrics();
                    String[] lines = {"Codes appear", "once the tree", "is built."};
                    int y = (getHeight() - lines.length * (fm.getHeight() + 4)) / 2 + fm.getAscent();
                    for (String line : lines) {
                        g2.drawString(line, (getWidth() - fm.stringWidth(line)) / 2, y);
                        y += fm.getHeight() + 4;
                    }
                    g2.dispose();
                }
            }

            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                return row >= 0 && model.isShown(row) ? model.describe(row) : null;   // the full code, which can be too long for the cell
            }
        };
        table.setFont(Theme.FONT_LABEL.deriveFont(14f));
        table.setBackground(Theme.EDITOR_BG);
        table.setForeground(Theme.TEXT_PRIMARY);
        table.setSelectionBackground(Theme.SELECTION);
        table.setSelectionForeground(Theme.TEXT_PRIMARY);
        table.setShowGrid(false);   // a row has its line only once its spirit has landed (see Cell)
        table.setRowHeight(ROW_HEIGHT);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setFocusable(false);
        table.setRowSelectionAllowed(false);
        table.setDefaultRenderer(Object.class, new Cell(false));
        table.getTableHeader().setDefaultRenderer(new Cell(true));
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(false);
        table.getColumnModel().getColumn(0).setMinWidth(66);   // room for the header's "SYMBOL"
        table.getColumnModel().getColumn(0).setPreferredWidth(100);
        table.getColumnModel().getColumn(1).setPreferredWidth(260);
        return table;
    }

    /**
     * A cell of the table, or of its header. The byte is centered; the code is left-aligned in
     * a monospaced font, shrunk when it is too long for the column, and flashes as it lands.
     */
    private static final class Cell extends DefaultTableCellRenderer {

        private static final EmptyBorder PAD = new EmptyBorder(0, 8, 0, 8);
        private final boolean header;

        Cell(boolean header) {
            this.header = header;
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            String text = value == null ? "" : value instanceof Integer symbol ? CubeField.label(symbol) : value.toString();
            super.getTableCellRendererComponent(table, text, false, false, row, column);
            setHorizontalAlignment(column == 0 ? CENTER : LEFT);
            if (header) {
                setFont(Theme.FONT_TITLE);
                setForeground(Theme.TEXT_MUTED);
                setBackground(Theme.EDITOR_BG);
                setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Theme.DIVIDER), new EmptyBorder(9, 6, 9, 6)));
                return this;
            }
            setBorder(value == null ? PAD : new CompoundBorder(new MatteBorder(0, 0, 1, 0, Theme.DIVIDER), PAD));   // a row that has not landed has no line
            Font font = column == 0 ? Theme.FONT_TITLE.deriveFont(14f) : Theme.FONT_EDITOR.deriveFont(13f);
            if (column == 1) {
                int room = table.getColumnModel().getColumn(1).getWidth() - 16;
                for (float size = 13; size > 9 && getFontMetrics(font).stringWidth(text) > room; ) {
                    font = font.deriveFont(--size);
                }
            }
            setFont(font);
            setForeground(column == 1 ? Theme.ACCENT : value instanceof Integer symbol && CubeField.isSpecial(symbol) ? Theme.VIOLET : Theme.TEXT_PRIMARY);
            setBackground(Theme.mix(Theme.EDITOR_BG, Theme.ACCENT, 0.35 * ((CodeModel) table.getModel()).flash(row)));
            return this;
        }
    }

    /** Where a row's symbol cell is, in the stage's coordinates, kept inside the part of the table that can be seen. */
    private Rectangle rowRect(int row) {
        Rectangle cell = SwingUtilities.convertRectangle(table, table.getCellRect(row, 0, true), stage);
        Rectangle view = SwingUtilities.convertRectangle(scroll.getViewport(), new Rectangle(scroll.getViewport().getSize()), stage);
        cell.y = Math.max(view.y, Math.min(view.y + view.height - cell.height, cell.y));
        return cell;
    }

    /** A spirit has landed: its row shows its byte and code. */
    private void landed(int row) {
        model.show(row);
        landed++;
    }

    // -- Running -------------------------------------------------------
    /** Call once the panel is on the screen. Instant, this shows everything; animated, it starts the show. */
    public void start() {
        last = System.nanoTime();
        phaseStart = last;
        if (!animated && ready) {
            showAll();
        } else {
            timer.start();
        }
    }

    /** Back button: stops the animation and the reading of the input, if still going. */
    private void stop() {
        stopped = true;
        timer.stop();
        scan.cancel();
    }

    /** The input is read, so the cubes can be shown. */
    private void inputRead() {
        if (stopped) {
            return;
        }
        if (scan.total() == 0) {
            fail("There is nothing to read.");
            return;
        }
        tree = new HuffmanTree(scan.counts());
        order = tree.byFrequency();
        long bits = 0;
        long table = 0;
        for (int symbol : order) {
            bits += (long) scan.counts()[symbol] * tree.code(symbol).length();
            table += 8 + tree.code(symbol).length();
        }
        tableBits = table;
        statValue[0] = 8.0 * scan.total();
        statValue[1] = bits + 8.0 * order.length;   // the codes, to the bit, and the 8 bits of each symbol they stand for
        statValue[2] = 100.0 * (1 - statValue[1] / statValue[0]);   // the share of the input that is saved
        readMs = Math.max(MIN_READ_MS, Math.min(MAX_READ_MS, scan.total() * MS_PER_BYTE));
        ready = true;
        if (btnSkip != null) {
            showSkip(true);
        }
    }

    private void fail(String message) {
        if (stopped) {
            return;
        }
        phase = Phase.DONE;
        backReveal.progress = 1;   // nothing is showing, so there is no show to protect
        showBack(true);
        backBox.repaint();
        if (btnSkip != null) {
            skipReveal.progress = 0;
            showSkip(false);
        }
        cubes.setMessage(message, true);
        cubes.repaint();
        if (topSlot != null) {
            remove(topSlot);
            revalidate();
        }
    }

    private void showAll() {
        cubes.showAll(scan);
        cubes.showTree(tree);
        cubes.setCaption(treeCaption());
        model.fill(tree, true);
        cubes.fillAll();
        deliver(true);
        zoomReveal.progress = 1;
        zoomBox.repaint();
        cubes.repaint();
        next(Phase.DONE);
    }

    private String treeCaption() {
        return String.format("Huffman tree: %d symbol%s, %,d bytes", order.length, order.length == 1 ? "" : "s", scan.total());
    }

    private void next(Phase to) {
        if (to == Phase.DONE) {
            cubes.setInteractive(true);   // the tree is finished: it can be zoomed and dragged
            shield.setVisible(false);     // ...and the table can be used
        }
        phase = to;
        phaseStart = System.nanoTime();
    }

    /** One step of the show, every 15 ms. */
    private void tick() {
        long now = System.nanoTime();
        double seconds = (now - last) / 1e9;
        double ms = (now - phaseStart) / 1e6;
        last = now;
        switch (phase) {
            case WAIT -> {
                if (ready && animated) {
                    next(Phase.READ);
                } else if (ready) {
                    showAll();
                } else {
                    double done = (double) scan.bytesRead() / Math.max(1, scan.expected());
                    if (animated) {
                        reading.waiting(done);
                    } else {
                        cubes.setMessage("Reading the file... " + (int) (done * 100) + "%", false);
                    }
                }
            }
            case READ -> {
                double t = Math.min(1, ms / readMs);
                Checkpoint[] checkpoints = scan.checkpoints();
                int index = (int) Math.min(checkpoints.length - 1, t * (checkpoints.length - 1));
                if (index != shown) {
                    shown = index;
                    Checkpoint cp = checkpoints[index];
                    cubes.update(cp.counts(), scan.firstSeen());
                    reading.show(scan, cp);
                    int symbols = 0;
                    for (int count : cp.counts()) {
                        symbols += count > 0 ? 1 : 0;
                    }
                    cubes.setCaption(String.format("%d symbols, %,d of %,d bytes read", symbols, cp.position(), scan.total()));
                }
                if (t >= 1) {
                    reading.finish();
                    next(Phase.HOLD);
                }
            }
            case HOLD -> {
                if (ms >= HOLD_MS) {
                    cubes.setCaption(String.format("%d symbols in %,d bytes", order.length, scan.total()));
                    cubes.sort();
                    next(Phase.SETTLE);
                }
            }
            case SETTLE -> {
                double p = Math.min(1, ms / SETTLE_MS);
                slotHeight = (int) Math.round((boxHeight + GAP) * (1 - easeInOut(p)));
                if (p >= 1) {
                    remove(topSlot);   // gone, so the layout is exactly the instant one
                    next(Phase.REVEAL);
                }
                revalidate();
            }
            case REVEAL -> {
                double p = Math.min(1, ms / REVEAL_MS);
                reveal.progress = easeOut(p);
                tableBox.repaint();
                if (p >= 1) {
                    cubes.startTree(tree);
                    cubes.setGlide(0.14);
                    cubes.setCaption("The symbols line up, least common first");
                    next(Phase.ROW);
                }
            }
            case ROW -> {
                if (ms >= ROW_MS) {
                    int merges = tree.merges().length;
                    perMerge = Math.min(MERGE_MS, MAX_TREE_MS / Math.max(1, merges));
                    cubes.setGlide(Math.max(0.015, Math.min(0.08, perMerge / 1000 * 0.22)));   // a fast build must not lag behind itself
                    event = 0;
                    next(merges == 0 ? Phase.PAUSE : Phase.TREE);
                }
            }
            case TREE -> {
                int merges = tree.merges().length;
                while (event < 3 * merges && ms >= (event / 3 + STEP[event % 3]) * perMerge) {
                    int merge = event / 3;
                    switch (event % 3) {
                        case 0 -> {
                            cubes.select(merge);
                            cubes.setCaption(String.format("The two smallest: merge %d of %d", merge + 1, merges));
                        }
                        case 1 -> cubes.join(merge);
                        default -> cubes.slide(merge);
                    }
                    event++;
                }
                if (event == 3 * merges && ms >= merges * perMerge + 250) {
                    next(Phase.PAUSE);
                }
            }
            case PAUSE -> {
                if (ms >= PAUSE_MS) {
                    cubes.setGlide(0.08);
                    cubes.setCaption(treeCaption());
                    model.fill(tree, false);   // the rows are there, but blank until a spirit lands in each
                    spiritGap = Math.min(SPIRIT_GAP_MS, SPIRIT_MAX_MS / order.length);
                    next(Phase.SPIRITS);
                }
            }
            case SPIRITS -> {
                int due = Math.min(order.length, (int) (ms / spiritGap) + 1);
                for (; launched < due; launched++) {
                    launch(launched);
                }
                spirits.advance(seconds);
                follow(landed, seconds);
                if (landed == order.length) {
                    next(Phase.CARDS);
                }
            }
            case CARDS -> {   // the cards sweep in, still at 0
                follow(landed, seconds);   // the scroll has not quite reached the last row yet
                for (int i = 0; i < stats.length; i++) {
                    stats[i].arrive(easeOut((ms - i * STAT_GAP_MS) / STAT_MS), 0);
                }
                if (ms >= CARDS_MS) {
                    cubes.setCaption("The cannon charges: each symbol and code in the table breaks into its 0s and 1s");
                    cannon.setVisible(true);
                    shown = -1;
                    loader.show(scan, scan.checkpoints()[0]);   // the text is in the cannon before anything fires
                    next(Phase.CANNON);
                }
            }
            case CANNON -> {   // the tree makes room above itself and the cannon sweeps in over the root
                double p = Math.min(1, ms / CANNON_MS);
                cubes.setCannon(easeInOut(p));
                cannonReveal.progress = easeOut(p);
                placeCannon();
                scrollTo(scrollY < 0.5 ? 0 : scrollY * Math.exp(-seconds / 0.15));   // the clones leave the table from its first row
                if (p >= 1) {
                    startCharge();
                    next(Phase.CHARGE);
                }
            }
            case CHARGE -> {   // a clone leaves each row of the table, top to bottom; its digits fly to the cannon
                int due = Math.min(order.length, (int) (ms / cloneGap) + 1);
                for (; cloned < due; cloned++) {
                    clone(cloned);
                }
                follow(cloned, seconds);
                spirits.advance(seconds);
                cubes.setCharge(Math.min(1, landedBits / (double) tableBits));
                stats[1].arrive(1, landedBits);   // only the table's share: the beam brings the rest
                if (cloned == order.length && !spirits.active()) {
                    cubes.setCharging(false);
                    next(Phase.READY);
                }
            }
            case READY -> {   // fully charged: a beat before it fires
                spirits.advance(seconds);
                cubes.setCharge(1);
                if (ms >= READY_MS) {
                    cubes.setCaption("The cannon fires the input down the tree");
                    cubes.arm(scan.checkpoints(), readMs / 1000);
                    shown = -1;
                    next(Phase.FIRE);
                }
            }
            case FIRE -> {   // the cannon reads the input again, and fires what it reads
                cubes.fire(seconds);
                double p = cubes.fireProgress();
                Checkpoint[] checkpoints = scan.checkpoints();
                int index = (int) Math.min(checkpoints.length - 1, p * (checkpoints.length - 1));
                if (index != shown) {
                    shown = index;
                    loader.show(scan, checkpoints[index]);
                }
                deliver(false);
                if (p >= 1) {
                    loader.finish();
                    next(Phase.DRAIN);
                }
            }
            case DRAIN -> {   // all read; the last of it is still on its way down
                cubes.fire(seconds);
                cubes.setCharge(Math.max(0, 1 - ms / 400));
                deliver(false);
                if (!cubes.beamBusy() && ms >= 400) {   // the charge has drained, whatever the tree
                    cubes.endBeam();
                    deliver(true);   // exactly what was counted, not the beam's running sum
                    cubes.setCaption(treeCaption());
                    next(Phase.CLOSE);
                }
            }
            case CLOSE -> {   // the cannon sweeps out and the tree has its room back
                double p = easeInOut(Math.min(1, ms / CLOSE_MS));
                cubes.setCannon(1 - p);
                cannonReveal.progress = 1 - p;
                placeCannon();
                if (ms >= CLOSE_MS) {
                    cannon.setVisible(false);
                    showBack(true);
                    showSkip(false);   // the show is over: SKIP is on its way out and cannot be pressed
                    next(Phase.ZOOM);
                }
            }
            case ZOOM -> {
                zoomReveal.progress = easeOut(ms / STAT_MS);
                backReveal.progress = zoomReveal.progress;
                skipReveal.progress = 1 - zoomReveal.progress;
                zoomBox.repaint();
                backBox.repaint();
                skipBox.repaint();
                if (ms >= STAT_MS) {
                    next(Phase.DONE);
                }
            }
            case DONE -> {
                if (scrollY > 0 && ms >= 700) {   // after a moment, the table goes back to the top, as it is in instant mode
                    scrollTo(scrollY < 0.5 ? 0 : scrollY * Math.exp(-seconds / 0.15));
                }
            }
        }
        ms = (System.nanoTime() - phaseStart) / 1e6;   // the phase may just have changed: the last landing is the start of DONE
        boolean moving = cubes.tick(seconds);
        if (phase.compareTo(Phase.SPIRITS) >= 0 && phase.compareTo(Phase.READY) <= 0) {
            stage.repaint();   // the spirits, the clones and their digits: and, through CARDS and CANNON, until the last spirit's glow and the last rows' flashes have died away
        }
        if (phase == Phase.DONE && !moving && ms >= 450 && !spirits.active() && scrollY == 0) {
            timer.stop();
        }
    }

    /** Sends the spirit of the byte in row {@code row} of the table from its cube. */
    private void launch(int row) {
        int symbol = order[row];
        double[] cube = cubes.leafBounds(symbol);
        Point from = SwingUtilities.convertPoint(cubes, (int) Math.round(cube[0]), (int) Math.round(cube[1]), stage);
        spirits.launch(symbol, row, from.x, from.y, cube[2]);
        cubes.release(symbol);
    }

    /** Scrolls the table so that the rows being worked on stay in view, a little ahead of the last one of the {@code done}. */
    private void follow(int done, double seconds) {
        int view = scroll.getViewport().getExtentSize().height;
        double target = Math.max(0, Math.min(order.length * ROW_HEIGHT - view, (done + 3) * ROW_HEIGHT - view));
        scrollTo(scrollY + (target - scrollY) * (1 - Math.exp(-seconds / 0.10)));
    }

    private void scrollTo(double y) {
        scrollY = y;
        scroll.getViewport().setViewPosition(new Point(0, (int) Math.round(y)));
    }

    /**
     * The cards show what the cannon's beam has delivered so far, or, when {@code all}, the exact
     * totals. The compressed size is the table's share, from the charge, plus what the beam has delivered beyond it.
     */
    private void deliver(boolean all) {
        double raw = all ? statValue[0] : 8 * cubes.arrivedBytes();
        double packed = all ? statValue[1] : tableBits + cubes.arrivedRepeatBits();
        double saved = all ? statValue[2] : raw == 0 ? 0 : 100 * (1 - cubes.arrivedBits() / raw - 8.0 * order.length / statValue[0]);   // the symbols cost the same share of the input all along
        double[] values = {raw, packed, saved};
        for (int i = 0; i < stats.length; i++) {
            stats[i].arrive(1, values[i]);
        }
    }

    /** Puts the cannon's box over the root, where the tree is now. */
    private void placeCannon() {
        cannon.setBounds(cubes.cannonBounds());
        cannon.validate();   // its box is laid out at the new size, or its title and the text have no room
    }

    /** Where the cannon's core is, in the stage's coordinates. */
    private Point coreAt() {
        double[] core = cubes.core();
        return SwingUtilities.convertPoint(cubes, (int) Math.round(core[0]), (int) Math.round(core[1]), stage);
    }

    /** The BACK button is there, and works, only once the show is over. */
    private void showBack(boolean on) {
        btnBack.setEnabled(on);
        btnBack.setCursor(Cursor.getPredefinedCursor(on ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        backBox.setVisible(on);   // it sits over SKIP, so while it is off it must not take the mouse
    }

    /** SKIP can be pressed from the moment the input is read until the show is over, or while it is being confirmed. */
    private void showSkip(boolean on) {
        btnSkip.setEnabled(on);
        btnSkip.setCursor(Cursor.getPredefinedCursor(on ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    /** A button in the middle of the row's slot; BACK and SKIP both stand in one. */
    private static JPanel slot(JButton button) {
        JPanel slot = new JPanel(new FlowLayout(FlowLayout.CENTER, 24, 0));
        slot.setOpaque(false);
        slot.add(button);
        return slot;
    }

    /**
     * SKIP: the show stops where it is while the question is open. On YES it stays stopped, SKIP
     * can no longer be pressed, and {@code onSkip} takes over; on NO the show carries on from the same point.
     */
    private void skip(Runnable onSkip) {
        timer.stop();
        long stoppedAt = System.nanoTime();
        if (ConfirmDialog.skip(this)) {
            showSkip(false);
            onSkip.run();
        } else {
            phaseStart += System.nanoTime() - stoppedAt;   // the time spent deciding is not part of the show
            last = System.nanoTime();
            timer.start();
        }
    }

    /** The whole of row {@code row} of the table, in the stage's coordinates, kept inside the part of the table that can be seen. */
    private Rectangle rowBox(int row) {
        Rectangle symbol = rowRect(row);
        Rectangle code = SwingUtilities.convertRectangle(table, table.getCellRect(row, 1, true), stage);
        return new Rectangle(symbol.x, symbol.y, code.x + code.width - symbol.x, symbol.height);
    }

    /** The charge begins, with the table at its first row. */
    private void startCharge() {
        cloneGap = Math.min(CLONE_MAX_MS / order.length, Math.max(CLONE_GAP_MS, CLONE_MIN_MS / order.length));
        cloned = 0;
        landedBits = 0;
        scrollTo(0);
    }

    /** A clone of row {@code row} of the table rises and breaks into the 8 bits of its symbol and every digit of its code, one bit each, which fly to the cannon. */
    private void clone(int row) {
        int symbol = order[row];
        String code = tree.code(symbol);
        Rectangle box = rowBox(row);
        int codeX = box.x + table.getColumnModel().getColumn(0).getWidth() + 8;   // the cell's padding
        Font font = table.prepareRenderer(table.getCellRenderer(row, 1), row, 1).getFont();   // the table shrinks a long code to fit its cell
        spirits.cloneRow(symbol, code, font, box, codeX, cloneGap / 1000);
    }

    private static double easeOut(double p) {
        return 1 - Math.pow(1 - Math.max(0, Math.min(1, p)), 3);
    }

    private static double easeInOut(double p) {
        p = Math.max(0, Math.min(1, p));
        return p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2;
    }

    /**
     * One of the three cards: what it is, and a number that counts up as the card arrives. It
     * is wrapped in a layer ({@link #card}) that sweeps it in from the side.
     */
    private static final class Stat extends JPanel {

        final JLayer<JPanel> card;
        private final Reveal reveal = new Reveal(false, 60);
        private final String title;
        private final boolean percent;
        private double value;

        Stat(String title, boolean percent) {
            this.title = title;
            this.percent = percent;
            setOpaque(false);
            reveal.progress = 0;
            card = new JLayer<>(this, reveal);
        }

        /** The card is {@code progress} (0 to 1) of the way in, and shows {@code value}. */
        void arrive(double progress, double value) {
            reveal.progress = progress;
            this.value = value;
            card.repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.EDITOR_BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
            Theme.frame(g2, 0, 0, getWidth(), getHeight(), 10);
            g2.setFont(Theme.FONT_TITLE);
            g2.setColor(Theme.TEXT_MUTED);
            g2.drawString(title, 16, 25);
            g2.setFont(Theme.FONT_EDITOR.deriveFont(Font.BOLD, 20f));
            g2.setColor(Theme.VIOLET);
            g2.drawString(percent ? String.format("%.2f%%", value) : String.format("%,d bits", Math.round(value)), 16, 52);
            g2.dispose();
        }
    }

    /**
     * Shows its box behind a sweeping line, fading in, and sliding in from the side if it has
     * a {@code slide}; the line is the same kind of light as the one in the TV effect. At
     * {@code progress} 1 it just paints the box.
     */
    private static final class Reveal extends LayerUI<JPanel> {

        private final boolean across;   // sweeps from left to right instead of from top to bottom
        private final int slide;        // how far to the right of its place it starts, in pixels
        double progress = 1;

        Reveal(boolean across, int slide) {
            this.across = across;
            this.slide = slide;
        }

        @Override
        public void paint(Graphics g, JComponent c) {
            if (progress >= 1) {
                super.paint(g, c);
                return;
            }
            if (progress <= 0) {
                return;
            }
            int reached = (int) ((across ? c.getWidth() : c.getHeight()) * progress);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.clipRect(0, 0, across ? reached : c.getWidth(), across ? c.getHeight() : reached);
            g2.translate((int) Math.round(slide * (1 - progress)), 0);
            g2.setComposite(AlphaComposite.SrcOver.derive((float) (0.2 + 0.8 * progress)));
            super.paint(g2, c);
            g2.dispose();

            Graphics2D line = (Graphics2D) g.create();
            if (across) {
                line.transform(new AffineTransform(0, 1, 1, 0, 0, 0));   // drawn as if sweeping down, then turned on its side
            }
            int wide = across ? c.getHeight() : c.getWidth();
            Color glow = Theme.TEXT_PRIMARY;
            int fade = (int) (255 * Math.min(1, (1 - progress) * 3));   // the line dies out at the very end
            line.setPaint(new GradientPaint(0, reached - 18, new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), 0),
                0, reached, new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), fade / 2)));
            line.fillRect(0, reached - 18, wide, 18);
            line.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), fade));
            line.fillRect(0, reached, wide, 2);
            line.dispose();
        }
    }
}
