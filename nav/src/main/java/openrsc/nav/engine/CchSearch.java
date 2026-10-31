package openrsc.nav.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * One thread's query state over a {@link CchMetric}: a {@link #forward} half up from source seeds and
 * a {@link #backward} half up from target seeds, met at their cheapest common rank. Labels are packed
 * as the metric's weights.
 *
 * <p>A half walks its seeds' elimination-tree ancestors in rank order, not by a priority queue:
 * every upward edge leads to an ancestor, so relaxing in rank order settles each rank before it is
 * read. The work is bounded by the tree's depth and upward degree, whatever the distance. The
 * halves are independent, so one forward half can be met against many backward ones.
 *
 * <p>Not thread-safe; reused query after query.
 */
final class CchSearch {

    private static final long INF = CchMetric.INF;

    final CchTopology topo;
    final Half forward;
    final Half backward;
    private int meetRank = -1;

    CchSearch(CchTopology topo) {
        this.topo = topo;
        this.forward = new Half(topo, true);
        this.backward = new Half(topo, false);
    }

    /** One direction's labels over the ranks its seeds reach, kept as bits so a run walks them in order. */
    static final class Half {
        private final CchTopology topo;
        private final boolean forward;
        /** Per rank, the packed cost from the seeds, or to them for a backward half; valid only where reached. */
        final long[] dist;
        /** Per reached rank, the edge that last improved it; -1 at a seed and where none did. */
        final int[] pred;

        private final int[] mark;
        private final long[] bits;
        private int loWord = Integer.MAX_VALUE;

        private int hiWord = -1;
        private int[] list = new int[256];

        private int size;
        private int stamp;
        private CchMetric heldMetric;

        private Object heldSource;

        Half(CchTopology topo, boolean forward) {
            this.topo = topo;
            this.forward = forward;
            int n = topo.nodeCount;
            dist = new long[n];
            pred = new int[n];
            mark = new int[n];
            bits = new long[(n + 63) >>> 6];
        }

        void reset() {
            if (++stamp == Integer.MAX_VALUE) {
                Arrays.fill(mark, 0);
                stamp = 1;
            }
            for (int w = loWord; w <= hiWord; w++) {
                bits[w] = 0L;
            }
            loWord = Integer.MAX_VALUE;
            hiWord = -1;
            size = 0;
            heldMetric = null;
            heldSource = null;
        }

        /** Vouches that this half, as just run under {@code m}, was seeded from {@code source} alone. */
        void hold(CchMetric m, Object source) {
            heldMetric = m;
            heldSource = source;
        }

        /** Whether the run {@link #hold} vouched for still stands: nothing has reset or seeded the half since. */
        boolean holds(CchMetric m, Object source) {
            return heldMetric == m && source.equals(heldSource);
        }

        private void markRank(int r) {
            mark[r] = stamp;
            int w = r >>> 6;
            bits[w] |= 1L << r;
            if (w < loWord) {
                loWord = w;
            }
            if (w > hiWord) {
                hiWord = w;
            }
        }

        /**
         * Seeds {@code node} at {@code cost}, the lower of two seeds on one node kept; true when this one is
         * its label now.
         */
        boolean seed(int node, long cost) {
            heldMetric = null;
            heldSource = null;
            int r = topo.rankOf[node];
            if (mark[r] == stamp) {
                if (cost < dist[r]) {
                    dist[r] = cost;
                    pred[r] = -1;
                    return true;
                }
                return false;
            }
            markRank(r);
            dist[r] = cost;
            pred[r] = -1;
            for (int a = topo.parent[r]; a != CchTopology.NO_PARENT && mark[a] != stamp; a = topo.parent[a]) {
                markRank(a);
                dist[a] = INF;
                pred[a] = -1;
            }
            return true;
        }

        /** Settles every reached rank under {@code m}, lowest first; a backward half reads the head-to-tail weights. */
        void run(CchMetric m) {
            int[] firstUp = topo.firstUp;
            int[] head = topo.head;
            long[] w = forward ? m.up : m.down;
            size = 0;
            for (int word = loWord; word <= hiWord; word++) {
                long set = bits[word];
                while (set != 0L) {
                    int r = word << 6 | Long.numberOfTrailingZeros(set);
                    set &= set - 1;
                    push(r);
                    long d = dist[r];
                    if (d == INF) {
                        continue;
                    }
                    for (int e = firstUp[r]; e < firstUp[r + 1]; e++) {
                        long c = d + w[e];
                        int y = head[e];
                        if (c < dist[y]) {
                            dist[y] = c;
                            pred[y] = e;
                        }
                    }
                }
            }
        }

        boolean reached(int rank) {
            return (bits[rank >>> 6] & (1L << rank)) != 0L;
        }

        int size() {
            return size;
        }

        /** The {@code i}-th reached rank, ascending, of the last run's {@link #size}. */
        int rankAt(int i) {
            return list[i];
        }

        private void push(int r) {
            if (size == list.length) {
                list = Arrays.copyOf(list, size * 2);
            }
            list[size++] = r;
        }
    }

    /**
     * The cheapest packed cost at a rank both halves reached, {@link CchMetric#INF} when there is none.
     * Call after both have run. Of equal minima the lowest rank wins.
     */
    long meet() {
        Half small = forward.size() <= backward.size() ? forward : backward;
        Half other = small == forward ? backward : forward;
        long best = INF;
        int at = -1;
        for (int i = 0; i < small.size(); i++) {
            int r = small.rankAt(i);
            if (!other.reached(r)) {
                continue;
            }
            long a = forward.dist[r];
            long b = backward.dist[r];
            if (a != INF && b != INF && a + b < best) {
                best = a + b;
                at = r;
            }
        }
        meetRank = at;
        return best;
    }

    /**
     * The route {@link #meet} chose, as {@code {node, tag}} pairs in travel order, each tag the arc
     * walked into its node: {@link Engine#TAG_SEED} at the root, or the teleport's transport when the
     * route opens with a cast from the start tile, rooted at the landing. A cast source is never a link:
     * a cast part way is the node cast from, then the landing tagged with the teleport. Null when
     * nothing met.
     */
    List<int[]> chain(CchMetric m) {
        if (meetRank < 0) {
            return null;
        }
        IntArrayList downEdges = new IntArrayList();
        for (int r = meetRank; backward.pred[r] >= 0; r = topo.tail[backward.pred[r]]) {
            downEdges.add(backward.pred[r]);
        }
        return chain(m, meetRank, forwardEdges(meetRank), downEdges);
    }

    /**
     * {@link #chain(CchMetric)} for the forward half against a goal's backward half kept as labels: its
     * reached ranks ascending, each one's cost to the goal and the edge that set it. Meets where
     * {@link #meet} would; null when nothing does.
     */
    List<int[]> chainToLabels(CchMetric m, int[] ranks, long[] costs, int[] preds) {
        long best = INF;
        int at = -1;
        for (int i = 0; i < ranks.length; i++) {
            int r = ranks[i];
            if (forward.reached(r) && forward.dist[r] != INF && forward.dist[r] + costs[i] < best) {
                best = forward.dist[r] + costs[i];
                at = r;
            }
        }
        if (at < 0) {
            return null;
        }
        return chain(m, at, forwardEdges(at), labelledEdges(at, ranks, preds));
    }

    /** {@link #chain(CchMetric)} for the forward half to bank {@code bank}, read off {@link CchMetric#toBankLabels}. */
    List<int[]> chainToBank(CchMetric m, int bank) {
        long[] labels = m.toBankLabels();
        int banks = topo.graph.bankNodeCount();
        long best = INF;
        int at = -1;
        for (int k = 0; k < topo.bankRanks.length; k++) {
            int r = topo.bankRanks[k];
            long b = labels[k * banks + bank];
            if (b != INF && forward.reached(r) && forward.dist[r] != INF && forward.dist[r] + b < best) {
                best = forward.dist[r] + b;
                at = r;
            }
        }
        if (at < 0) {
            return null;
        }
        return chain(m, at, forwardEdges(at), bankEdges(at, bank, m.toBankPreds()));
    }

    /**
     * {@link #chain(CchMetric)} from bank {@code bank} to a goal kept as labels, the bank's side read
     * off {@link CchMetric#fromBankLabels}; neither half is touched.
     */
    List<int[]> chainFromBank(CchMetric m, int bank, int[] ranks, long[] costs, int[] preds) {
        long[] labels = m.fromBankLabels();
        int banks = topo.graph.bankNodeCount();
        long best = INF;
        int at = -1;
        for (int i = 0; i < ranks.length; i++) {
            int slot = topo.bankSlot[ranks[i]];
            if (slot < 0) {
                continue;
            }
            long a = labels[slot * banks + bank];
            if (a != INF && a + costs[i] < best) {
                best = a + costs[i];
                at = ranks[i];
            }
        }
        if (at < 0) {
            return null;
        }
        return chain(m, at, bankEdges(at, bank, m.fromBankPreds()), labelledEdges(at, ranks, preds));
    }

    private IntArrayList forwardEdges(int r) {
        IntArrayList edges = new IntArrayList();
        for (; forward.pred[r] >= 0; r = topo.tail[forward.pred[r]]) {
            edges.add(forward.pred[r]);
        }
        return edges;
    }

    private IntArrayList labelledEdges(int r, int[] ranks, int[] preds) {
        IntArrayList edges = new IntArrayList();
        for (int e = preds[Arrays.binarySearch(ranks, r)];
                e >= 0;
                e = preds[Arrays.binarySearch(ranks, topo.tail[e])]) {
            edges.add(e);
        }
        return edges;
    }

    private IntArrayList bankEdges(int r, int bank, int[] preds) {
        int banks = topo.graph.bankNodeCount();
        IntArrayList edges = new IntArrayList();
        for (int e = preds[topo.bankSlot[r] * banks + bank];
                e >= 0;
                e = preds[topo.bankSlot[topo.tail[e]] * banks + bank]) {
            edges.add(e);
        }
        return edges;
    }

    private List<int[]> chain(CchMetric m, int meet, IntArrayList upEdges, IntArrayList downEdges) {
        IntArrayList to = new IntArrayList();
        IntArrayList tags = new IntArrayList();
        int root = upEdges.isEmpty() ? meet : topo.tail[upEdges.getInt(upEdges.size() - 1)];
        int startNode = topo.nodeAt[root];
        for (int i = upEdges.size() - 1; i >= 0; i--) {
            unpack(m, upEdges.getInt(i), true, to, tags);
        }
        for (int i = 0; i < downEdges.size(); i++) {
            unpack(m, downEdges.getInt(i), false, to, tags);
        }
        List<int[]> chain = new ArrayList<>(to.size() + 1);
        int k = 0;
        if (topo.isSource(startNode)) {
            chain.add(new int[] {to.getInt(0), tags.getInt(0)});
            k = 1;
        } else {
            chain.add(new int[] {startNode, Engine.TAG_SEED});
        }
        for (; k < to.size(); k++) {
            if (!topo.isSource(to.getInt(k))) {
                chain.add(new int[] {to.getInt(k), tags.getInt(k)});
            }
        }
        return chain;
    }

    private void unpack(CchMetric m, int e, boolean up, IntArrayList to, IntArrayList tags) {
        int via = m.via(e, up);
        if (via < 0) {
            to.add(topo.nodeAt[up ? topo.head[e] : topo.tail[e]]);
            tags.add(CchMetric.tagOf(via));
            return;
        }
        // vx joins the middle node to the tail, vy to the head.
        int vx = topo.triangles[2 * via];
        int vy = topo.triangles[2 * via + 1];
        if (up) {
            unpack(m, vx, false, to, tags);
            unpack(m, vy, true, to, tags);
        } else {
            unpack(m, vy, false, to, tags);
            unpack(m, vx, true, to, tags);
        }
    }
}
