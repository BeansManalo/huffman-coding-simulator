package hcs.gui.process;

import hcs.core.FrequencyScan;
import hcs.core.FrequencyScan.Checkpoint;
import hcs.gui.Theme;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Arrays;
import javax.swing.JComponent;

/**
 * Shows the part of the input that is being read. A short input is shown whole, with what is
 * read so far highlighted and a block cursor on the byte being read. A longer one is shown as
 * a window onto the file: the line being read and what follows it, with byte offsets on the
 * left, scrolling along as the reading advances.
 */
@SuppressWarnings("serial")
final class ReadingView extends JComponent {

    private static final int PAD_X = 16;
    private static final int PAD_Y = 12;
    private static final int FOOTER = 29;
    /** Violet, dimmed, for bytes that have no visible character. */
    private static final Color SPECIAL = new Color(Theme.VIOLET.getRed(), Theme.VIOLET.getGreen(), Theme.VIOLET.getBlue(), 150);

    private final Font font = Theme.FONT_EDITOR.deriveFont(14f);
    private final Font note = Theme.FONT_TITLE;
    private final int[] row = new int[FrequencyScan.SNIPPET_BYTES];
    private final int[] col = new int[FrequencyScan.SNIPPET_BYTES];
    private final int rows;
    private final String verb;
    private final int[] firstInRow;
    private final char[] one = new char[1];
    private FrequencyScan scan;
    private Checkpoint checkpoint;
    private boolean done;
    private double waiting = -1;

    ReadingView() {
        this("Reading", 3);
    }

    /** A view that shows {@code rows} lines of text and calls what it does {@code verb} in its footer. */
    ReadingView(String verb, int rows) {
        this.verb = verb;
        this.rows = rows;
        firstInRow = new int[rows];
        setOpaque(true);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(0, 2 * PAD_Y + rows * lineHeight(getFontMetrics(font)) + FOOTER);
    }

    private static int lineHeight(FontMetrics fm) {
        return fm.getHeight() + 4;
    }

    // -- What to show --------------------------------------------------
    /** Before the reading starts: the file is still being read, {@code progress} (0 to 1) of the way. */
    void waiting(double progress) {
        waiting = progress;
        repaint();
    }

    /** The state to show: the bytes read so far, and the text around the last one. */
    void show(FrequencyScan scan, Checkpoint checkpoint) {
        this.scan = scan;
        this.checkpoint = checkpoint;
        waiting = -1;
        repaint();
    }

    /** Everything is read: the cursor goes and the whole text counts as read. */
    void finish() {
        done = true;
        repaint();
    }

    // -- Painting ------------------------------------------------------
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        int w = getWidth();
        int h = getHeight();
        g2.setColor(Theme.EDITOR_BG);
        g2.fillRect(0, 0, w, h);

        g2.setFont(font);
        FontMetrics fm = g2.getFontMetrics();
        int lh = lineHeight(fm);
        int footerTop = 2 * PAD_Y + rows * lh;
        g2.setColor(Theme.DIVIDER);
        g2.fillRect(0, footerTop, w, 1);

        if (scan == null) {
            g2.setFont(note);
            g2.setColor(Theme.TEXT_MUTED);
            String text = waiting < 0 ? "Getting ready..." : "Reading the file...";
            g2.drawString(text, PAD_X, PAD_Y + g2.getFontMetrics().getAscent());
        } else {
            paintText(g2, fm, lh, w);
        }
        paintFooter(g2, footerTop, w, h);
        g2.dispose();
    }

    private void paintText(Graphics2D g2, FontMetrics fm, int lh, int w) {
        byte[] head = scan.head();
        int fullCols = Math.max(1, (w - 2 * PAD_X) / fm.charWidth('M'));
        // A short input that fits in the box is shown whole; anything else as a window.
        boolean whole = scan.total() <= head.length && place(head, fullCols) <= rows;
        byte[] text = whole ? head : checkpoint.snippet();
        int cw = fm.charWidth('M');
        int gutter = whole ? 0 : 10 * cw;   // eight hex digits for the offset, and room for the pointer
        place(text, Math.max(1, (w - 2 * PAD_X - gutter) / cw));

        int reading = (int) (checkpoint.position() - 1 - (whole ? 0 : checkpoint.snippetStart()));   // the byte just read
        int firstRow = 0;
        if (!whole) {
            for (int i = Math.min(reading, text.length - 1); i >= 0; i--) {
                if (row[i] >= 0) {
                    firstRow = row[i];
                    break;
                }
            }
        }
        int cursor = done ? Integer.MAX_VALUE : reading;   // once done there is no cursor, and all of it is read

        Arrays.fill(firstInRow, -1);
        for (int i = 0; i < text.length; i++) {
            int r = row[i] - firstRow;
            if (row[i] < 0 || r < 0 || r >= rows) {
                continue;
            }
            if (firstInRow[r] < 0) {
                firstInRow[r] = i;
            }
            int x = PAD_X + gutter + col[i] * cw;
            int y = PAD_Y + r * lh;
            if (i == cursor) {
                g2.setColor(Theme.CARET);
                g2.fillRect(x, y, cw, lh);
            } else if (i < cursor) {
                g2.setColor(Theme.SELECTION);
                g2.fillRect(x, y, cw, lh);
            }
            int b = text[i] & 0xFF;
            one[0] = glyph(b);
            if (one[0] != ' ') {
                g2.setColor(i == cursor ? Theme.CHROME_BG : b < 0x20 || b >= 0x7F ? SPECIAL : i <= reading ? Theme.TEXT_PRIMARY : Theme.TEXT_MUTED);
                g2.drawChars(one, 0, 1, x, y + (lh - fm.getHeight()) / 2 + fm.getAscent());
            }
        }

        if (!whole) {
            g2.setColor(Theme.TEXT_MUTED);
            for (int r = 0; r < rows; r++) {
                if (firstInRow[r] >= 0) {
                    g2.drawString(String.format("%08X", checkpoint.snippetStart() + firstInRow[r]), PAD_X,
                        PAD_Y + r * lh + (lh - fm.getHeight()) / 2 + fm.getAscent());
                }
            }
            if (!done) {   // a pointer at the line being read
                int px = PAD_X + 8 * cw + 6;
                int py = PAD_Y + lh / 2;
                g2.setColor(Theme.CARET);
                g2.fillPolygon(new int[] {px, px + 7, px}, new int[] {py - 5, py, py + 5}, 3);
            }
        }
    }

    private void paintFooter(Graphics2D g2, int footerTop, int w, int h) {
        g2.setFont(note);
        FontMetrics fm = g2.getFontMetrics();
        int baseline = footerTop + 1 + (h - footerTop - 4 - fm.getHeight()) / 2 + fm.getAscent();
        String left;
        String right;
        double fraction;
        if (scan == null) {
            left = verb;
            right = waiting < 0 ? "" : (int) (waiting * 100) + "%";
            fraction = Math.max(0, waiting);
        } else if (done) {
            left = "Done: " + scan.firstSeen().length + " symbols";
            right = String.format("%,d bytes", scan.total());
            fraction = 1;
        } else {
            left = verb;
            right = String.format("%,d / %,d bytes", checkpoint.position(), scan.total());
            fraction = (double) checkpoint.position() / scan.total();
        }
        g2.setColor(Theme.TEXT_MUTED);
        g2.drawString(left, PAD_X, baseline);
        g2.setColor(Theme.TEXT_PRIMARY);
        g2.drawString(right, w - PAD_X - fm.stringWidth(right), baseline);

        g2.setColor(Theme.DIVIDER);
        g2.fillRect(0, h - 3, w, 3);
        int filled = (int) Math.round(w * fraction);
        g2.setColor(Theme.ACCENT);
        g2.fillRect(0, h - 3, filled, 3);
        if (!done && filled > 0) {
            g2.setColor(Theme.CARET);
            g2.fillRect(Math.max(0, filled - 4), h - 3, 4, 3);
        }
    }

    // -- Where each byte goes ------------------------------------------
    /** Puts each byte in a cell of {@code row} and {@code col}, wrapping at {@code cols}; returns the rows used. */
    private int place(byte[] text, int cols) {
        int r = 0;
        int c = 0;
        for (int i = 0; i < text.length; i++) {
            if (text[i] == '\r') {
                row[i] = -1;   // takes no room, so Windows line ends look like Unix ones
                continue;
            }
            if (c == cols) {
                r++;
                c = 0;
            }
            row[i] = r;
            col[i] = c++;
            if (text[i] == '\n') {   // the line feed is a cell of its own, and ends the line
                r++;
                c = 0;
            }
        }
        return c == 0 ? r : r + 1;
    }

    /** The character drawn for a byte: itself, or a mark for a line feed, a tab, or anything else invisible. */
    private static char glyph(int b) {
        if (b >= 0x20 && b < 0x7F) {
            return (char) b;
        }
        return b == '\n' ? '\u00B6' : b == '\t' ? '\u00BB' : '\u00B7';
    }
}
