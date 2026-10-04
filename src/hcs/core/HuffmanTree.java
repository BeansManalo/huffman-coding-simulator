package hcs.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * The Huffman tree of some byte counts, built the textbook way: take the two smallest nodes,
 * join them under a new node, repeat until one node is left. Ties go to the node made first,
 * so the same counts always give the same tree.
 * <p>
 * Nodes are numbered in the order they are made: the bytes that occur first, from the least
 * to the most common (ties by byte value), then each joining node. A byte's code is the path
 * to it from the root: 0 for the smaller node of a pair, 1 for the other.
 */
public final class HuffmanTree {

    /** One step of the build: the two smallest nodes (left is the smaller) and the node that joins them. */
    public record Merge(int left, int right, int parent) {
    }

    private final int leaves;
    private final int[] weight;
    private final int[] symbol;
    private final int[] left;
    private final int[] right;
    private final int[] height;
    private final int[] leafOf = new int[256];
    private final Merge[] merges;
    private final String[] codes = new String[256];

    /** @param counts how many times each byte value occurs; at least one must */
    public HuffmanTree(int[] counts) {
        List<Integer> present = new ArrayList<>();
        for (int b = 0; b < 256; b++) {
            if (counts[b] > 0) {
                present.add(b);
            }
        }
        if (present.isEmpty()) {
            throw new IllegalArgumentException("nothing to encode");
        }
        present.sort(Comparator.<Integer>comparingInt(b -> counts[b]).thenComparingInt(b -> b));
        leaves = present.size();
        int nodes = 2 * leaves - 1;
        int[] w = new int[nodes];
        int[] s = new int[nodes];
        int[] l = new int[nodes];
        int[] r = new int[nodes];
        int[] h = new int[nodes];
        Arrays.fill(leafOf, -1);
        PriorityQueue<Integer> queue = new PriorityQueue<>((a, b) -> w[a] != w[b] ? Integer.compare(w[a], w[b]) : Integer.compare(a, b));
        for (int id = 0; id < leaves; id++) {
            s[id] = present.get(id);
            w[id] = counts[s[id]];
            l[id] = -1;
            r[id] = -1;
            leafOf[s[id]] = id;
            queue.add(id);
        }
        merges = new Merge[leaves - 1];
        for (int id = leaves; id < nodes; id++) {
            int a = queue.poll();
            int b = queue.poll();
            s[id] = -1;
            w[id] = w[a] + w[b];
            l[id] = a;
            r[id] = b;
            h[id] = 1 + Math.max(h[a], h[b]);
            merges[id - leaves] = new Merge(a, b, id);
            queue.add(id);
        }
        weight = w;
        symbol = s;
        left = l;
        right = r;
        height = h;
        assign(nodes - 1, new StringBuilder());
    }

    private void assign(int id, StringBuilder path) {
        if (left[id] < 0) {
            codes[symbol[id]] = path.length() == 0 ? "0" : path.toString();   // a lone byte still needs one bit
            return;
        }
        path.append('0');
        assign(left[id], path);
        path.setLength(path.length() - 1);
        path.append('1');
        assign(right[id], path);
        path.setLength(path.length() - 1);
    }

    /** How many different bytes there are; they are the nodes 0 up to this. */
    public int leafCount() {
        return leaves;
    }

    public int nodeCount() {
        return weight.length;
    }

    public int root() {
        return weight.length - 1;
    }

    /** How often the byte occurs, or for a joining node, its two nodes' counts added. */
    public int weight(int node) {
        return weight[node];
    }

    /** The byte of a leaf; -1 for a joining node. */
    public int symbol(int node) {
        return symbol[node];
    }

    /** 0 for a leaf, otherwise 1 more than the taller of the two nodes joined. */
    public int height(int node) {
        return height[node];
    }

    /** The build, step by step. */
    public Merge[] merges() {
        return merges;
    }

    /** The code of a byte that occurs, as a string of 0s and 1s. */
    public String code(int symbol) {
        return codes[symbol];
    }

    /** The bytes that occur, most common first (ties by byte value). */
    public int[] byFrequency() {
        Integer[] order = new Integer[leaves];
        for (int id = 0; id < leaves; id++) {
            order[id] = symbol[id];
        }
        Arrays.sort(order, Comparator.<Integer>comparingInt(b -> -weight[leafOf[b]]).thenComparingInt(b -> b));
        int[] result = new int[leaves];
        for (int i = 0; i < leaves; i++) {
            result[i] = order[i];
        }
        return result;
    }
}
