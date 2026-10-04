package hcs.core;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One pass over the input that counts how often each byte value occurs. Typed text is
 * counted as its UTF-8 bytes, so every kind of file goes through the same code.
 * <p>
 * Along the way it keeps checkpoints: the counts so far, plus a snippet of the text around
 * the byte just read. The screen replays them in whatever time it likes, so a huge file
 * animates as fast as a medium one without being read twice.
 */
public final class FrequencyScan {

    /** At most this many checkpoints are kept; an input with fewer bytes than this gets one per byte. */
    public static final int MAX_CHECKPOINTS = 2000;
    /** The longest snippet a checkpoint keeps, and the longest {@link #head()}. */
    public static final int SNIPPET_BYTES = 480;
    /** How far back from the byte just read a snippet may start, when its line starts earlier. */
    private static final int LOOK_BACK = 160;

    /**
     * The state after {@code position} bytes were read: their counts, and the text around the
     * last one. The snippet starts at {@code snippetStart} (its line's start when that is close).
     */
    public record Checkpoint(long position, int[] counts, long snippetStart, byte[] snippet) {
    }

    private final File file;
    private final byte[] data;
    private final long expected;
    private volatile long read;
    private volatile boolean cancelled;

    private long total;
    private Checkpoint[] checkpoints;
    private int[] firstSeen;
    private byte[] head = new byte[0];

    public FrequencyScan(byte[] data) {
        this.file = null;
        this.data = data;
        this.expected = data.length;
    }

    public FrequencyScan(File file) {
        this.file = file;
        this.data = null;
        this.expected = file.length();
    }

    // -- While it runs (any thread) --------------------------------------
    /** How many bytes {@link #run()} is expected to read; for showing progress only. */
    public long expected() {
        return expected;
    }

    public long bytesRead() {
        return read;
    }

    /** Stops {@link #run()} early; whatever it holds afterwards is not meant to be used. */
    public void cancel() {
        cancelled = true;
    }

    // -- After it ran ----------------------------------------------------
    public long total() {
        return total;
    }

    /** Index 0 is the start (nothing read yet); the last one is the whole input. */
    public Checkpoint[] checkpoints() {
        return checkpoints;
    }

    /** How many times each byte value occurs in the whole input. */
    public int[] counts() {
        return checkpoints[checkpoints.length - 1].counts();
    }

    /** The byte values that occur, in the order they first appear. */
    public int[] firstSeen() {
        return firstSeen;
    }

    /** The first bytes of the input (all of it when it is short). */
    public byte[] head() {
        return head;
    }

    /** Reads the whole input. Blocks, so call it off the UI thread. */
    public void run() throws IOException {
        try (InputStream in = file != null ? new FileInputStream(file) : new ByteArrayInputStream(data)) {
            scan(in);
        }
    }

    private void scan(InputStream in) throws IOException {
        long step = Math.max(1, (expected + MAX_CHECKPOINTS - 1) / MAX_CHECKPOINTS);
        List<Checkpoint> list = new ArrayList<>();
        list.add(null);   // the start; it needs the head, which is known after the first read
        int[] counts = new int[256];
        int[] order = new int[256];
        int distinct = 0;
        byte[] buf = new byte[1 << 16];
        long pos = 0;           // bytes counted so far
        long next = step;       // where the next checkpoint goes
        long base = 0;          // bytes before the current buffer
        int n;
        int lastN = 0;          // the last buffer that held anything, for the closing checkpoint
        long lastBase = 0;
        while (!cancelled && (n = in.readNBytes(buf, 0, buf.length)) > 0) {
            if (pos == 0) {
                head = Arrays.copyOf(buf, Math.min(n, SNIPPET_BYTES));
            }
            int i = 0;
            while (i < n) {
                long until = next - pos;
                int end = until >= n - i ? n : i + (int) until;
                pos += end - i;
                for (; i < end; i++) {
                    int b = buf[i] & 0xFF;
                    if (counts[b]++ == 0) {
                        order[distinct++] = b;
                    }
                }
                if (pos == next) {
                    list.add(checkpoint(pos, counts, buf, n, i, base));
                    next += step;
                }
            }
            lastN = n;
            lastBase = base;
            base += n;
            read = pos;
        }
        if (cancelled) {
            return;
        }
        // The input can end between checkpoints; the last one must hold everything.
        if (pos > 0 && (list.size() == 1 || list.get(list.size() - 1).position() != pos)) {
            list.add(checkpoint(pos, counts, buf, lastN, lastN, lastBase));
        }
        list.set(0, new Checkpoint(0, new int[256], 0, head));
        total = pos;
        checkpoints = list.toArray(new Checkpoint[0]);
        firstSeen = Arrays.copyOf(order, distinct);
    }

    /** A checkpoint after {@code pos} bytes, when {@code i} bytes of {@code buf} (holding {@code n}) were counted. */
    private static Checkpoint checkpoint(long pos, int[] counts, byte[] buf, int n, int i, long base) {
        int last = i - 1;   // the byte just read
        int from = Math.max(0, last - LOOK_BACK);
        for (int k = last; k > from; k--) {
            if (buf[k - 1] == '\n') {   // its line starts here
                from = k;
                break;
            }
        }
        from = Math.min(from, Math.max(0, n - SNIPPET_BYTES));
        return new Checkpoint(pos, counts.clone(), base + from, Arrays.copyOfRange(buf, from, from + Math.min(SNIPPET_BYTES, n - from)));
    }
}
