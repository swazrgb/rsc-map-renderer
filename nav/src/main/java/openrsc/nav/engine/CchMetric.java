package openrsc.nav.engine;

import java.util.Arrays;

/**
 * One routing class's weights on a {@link CchTopology}: each edge, both ways, weighs the cheapest
 * route between its ends that climbs no higher than they do. A routing class is what a route's
 * price depends on besides its ends: the danger field, which transports the account may use, and
 * what each costs.
 *
 * <p>Weights are packed ({@link #pack}): the weight, danger charge included, in the high 32 bits,
 * the time in deci-ticks in the low 32. Adding two packed values adds both, and comparing them
 * compares weight and then time, the order every choice in the engine is made in.
 *
 * <p>An edge stores its two weights and nothing else: which arc or triangle gave a weight is derived
 * again when a route is unpacked ({@link #via}). Immutable once built but for the bank labels it
 * builds on first ask; safe for concurrent use.
 */
final class CchMetric {

    /**
     * Unreachable. 2^61, so that adding two cannot overflow and a relaxation adds without testing: a
     * sum through INF never beats a stored weight, which is always real or exactly INF.
     */
    static final long INF = 1L << 61;

    final CchTopology topo;
    /** Per edge, tail to head. */
    final long[] up;
    /** Per edge, head to tail. */
    final long[] down;

    private final DangerField danger;
    private final boolean[] canUse;
    private final int[] tCosts;
    private volatile BankLabels toBankLabels;
    private volatile BankLabels fromBankLabels;

    private record BankLabels(long[] costs, int[] preds) {}

    private static final ThreadLocal<CchSearch> LABEL_SEARCH = new ThreadLocal<>();

    private CchMetric(CchTopology topo, DangerField danger, boolean[] canUse, int[] tCosts) {
        this.topo = topo;
        this.danger = danger;
        this.canUse = canUse;
        this.tCosts = tCosts;
        int m = topo.edgeCount();
        this.up = new long[m];
        this.down = new long[m];
        Arrays.fill(up, INF);
        Arrays.fill(down, INF);
    }

    private CchMetric(CchMetric base, DangerField danger, boolean[] canUse, int[] tCosts) {
        this.topo = base.topo;
        this.danger = danger;
        this.canUse = canUse;
        this.tCosts = tCosts;
        this.up = base.up.clone();
        this.down = base.down.clone();
    }

    /** Both in [0, 2^31). */
    static long pack(int weight, int time) {
        return (long) weight << 32 | (time & 0xFFFFFFFFL);
    }

    static int weightOf(long packed) {
        return (int) (packed >>> 32);
    }

    /** The chain tag a negative {@link #via} names. */
    static int tagOf(int via) {
        return -via - 4;
    }

    /**
     * The metric of a class differing from {@code from}'s, under the same danger field and costs, only
     * in whether the transports in {@code changed} are usable: {@code from}'s weights with what the
     * change reaches recomputed, lowest edge first, so each edge is final before any edge that reads it.
     * The same weights {@link #customize} gives. Where every change opens a transport, weights can only
     * fall, and only the triangles of edges that fell are offered again.
     */
    static CchMetric customizeChanged(
            CchMetric from, DangerField danger, boolean[] canUse, int[] tCosts, int[] changed) {
        CchMetric m = new CchMetric(from, danger, canUse, tCosts);
        CchTopology t = m.topo;
        boolean opensOnly = true;
        for (int tIdx : changed) {
            opensOnly &= canUse[tIdx];
        }
        long[] dirty = new long[(t.edgeCount() + 63) >>> 6];
        for (int tIdx : changed) {
            for (int i = t.transportArcFirst[tIdx]; i < t.transportArcFirst[tIdx + 1]; i++) {
                m.seedChanged(t.transportArcs[i], opensOnly, dirty);
            }
        }
        if (opensOnly) {
            m.propagateFalls(dirty);
        } else {
            m.propagateRecomputes(dirty);
        }
        return m;
    }

    private void seedChanged(int a, boolean opensOnly, long[] dirty) {
        int slot = topo.arcSlot(a);
        int e = slot >>> 1;
        if (opensOnly) {
            long[] side = (slot & 1) == 0 ? up : down;
            long w = arcWeight(a);
            if (w >= side[e]) {
                return;
            }
            side[e] = w;
        }
        dirty[e >>> 6] |= 1L << e;
    }

    // The non-short-circuit | is a deliberate branch-free test in the hot loop.
    @SuppressWarnings("ShortCircuitBoolean")
    private void propagateFalls(long[] dirty) {
        CchTopology t = topo;
        int[] usedByFirst = t.usedByFirst;
        int[] usedBy = t.usedBy;
        int[] owner = t.triangleOwner;
        int[] triangles = t.triangles;
        long[] up = this.up;
        long[] down = this.down;
        for (int w = 0; w < dirty.length; w++) {
            long bits;
            while ((bits = dirty[w]) != 0L) {
                int e = w << 6 | Long.numberOfTrailingZeros(bits);
                dirty[w] = bits & (bits - 1);
                for (int i = usedByFirst[e]; i < usedByFirst[e + 1]; i++) {
                    int k = usedBy[i];
                    int xy = owner[k];
                    int vx = triangles[2 * k];
                    int vy = triangles[2 * k + 1];
                    long viaHead = down[vx] + up[vy];
                    long viaTail = down[vy] + up[vx];
                    if (viaHead < up[xy] | viaTail < down[xy]) {
                        up[xy] = Math.min(up[xy], viaHead);
                        down[xy] = Math.min(down[xy], viaTail);
                        // xy's tail outranks e's, so its id is higher: the scan has not passed it yet.
                        dirty[xy >>> 6] |= 1L << xy;
                    }
                }
            }
        }
    }

    private void propagateRecomputes(long[] dirty) {
        CchTopology t = topo;
        for (int w = 0; w < dirty.length; w++) {
            long bits;
            while ((bits = dirty[w]) != 0L) {
                int e = w << 6 | Long.numberOfTrailingZeros(bits);
                dirty[w] = bits & (bits - 1);
                long oldUp = up[e];
                long oldDown = down[e];
                recompute(e);
                if (up[e] != oldUp || down[e] != oldDown) {
                    for (int i = t.usedByFirst[e]; i < t.usedByFirst[e + 1]; i++) {
                        int u = t.triangleOwner[t.usedBy[i]];
                        dirty[u >>> 6] |= 1L << u;
                    }
                }
            }
        }
    }

    private void recompute(int e) {
        CchTopology t = topo;
        up[e] = INF;
        down[e] = INF;
        for (int i = t.edgeArcFirst[e]; i < t.edgeArcFirst[e + 1]; i++) {
            int a = t.edgeArcs[i];
            long w = arcWeight(a);
            if (w != INF) {
                offer(t.arcSlot(a), w);
            }
        }
        long toHead = up[e];
        long toTail = down[e];
        int[] triangles = t.triangles;
        for (int k = t.triangleFirst[e]; k < t.triangleFirst[e + 1]; k++) {
            int vx = triangles[2 * k];
            int vy = triangles[2 * k + 1];
            long viaHead = down[vx] + up[vy];
            if (viaHead < toHead) {
                toHead = viaHead;
            }
            long viaTail = down[vy] + up[vx];
            if (viaTail < toTail) {
                toTail = viaTail;
            }
        }
        up[e] = toHead;
        down[e] = toTail;
    }

    /** Whether every transport that reads nothing of the account is usable here exactly when in {@code other}. */
    boolean agreesUngated(boolean[] other) {
        boolean[] gated = topo.gated;
        for (int i = 0; i < other.length; i++) {
            if (!gated[i] && canUse[i] != other[i]) {
                return false;
            }
        }
        return true;
    }

    /** What the metric store weighs this at: both weight arrays, and the bank labels, built or not. */
    long bytes() {
        return (long) up.length * Long.BYTES * 2
                + (long) topo.bankRanks.length * topo.graph.bankNodeCount() * (Long.BYTES + Integer.BYTES) * 2
                + 64;
    }

    /**
     * Every bank's backward labels: the packed cost from each of {@link CchTopology#bankRanks} to bank
     * {@code i}, at {@code [slot * banks + i]}, {@link #INF} where it does not reach. Built on first ask;
     * {@link #fromBankLabels} is the other way, built apart since a class is often asked only one.
     */
    long[] toBankLabels() {
        return bankLabels(true).costs();
    }

    /** Beside each of {@link #toBankLabels}, the edge it was set through: -1 at the bank and where none reached. */
    int[] toBankPreds() {
        return bankLabels(true).preds();
    }

    /** The cost from bank {@code i} to each bank rank, laid out as {@link #toBankLabels}. */
    long[] fromBankLabels() {
        return bankLabels(false).costs();
    }

    /** Beside each of {@link #fromBankLabels}, the edge it was set through, as {@link #toBankPreds}. */
    int[] fromBankPreds() {
        return bankLabels(false).preds();
    }

    private BankLabels bankLabels(boolean toBank) {
        BankLabels l = toBank ? toBankLabels : fromBankLabels;
        if (l == null) {
            synchronized (this) {
                l = toBank ? toBankLabels : fromBankLabels;
                if (l == null) {
                    l = computeBankLabels(toBank);
                    if (toBank) {
                        toBankLabels = l;
                    } else {
                        fromBankLabels = l;
                    }
                }
            }
        }
        return l;
    }

    private BankLabels computeBankLabels(boolean toBank) {
        int banks = topo.graph.bankNodeCount();
        long[] labels = new long[topo.bankRanks.length * banks];
        int[] preds = new int[labels.length];
        Arrays.fill(labels, INF);
        Arrays.fill(preds, -1);
        CchSearch s = LABEL_SEARCH.get();
        if (s == null || s.topo != topo) {
            s = new CchSearch(topo);
            LABEL_SEARCH.set(s);
        }
        CchSearch.Half half = toBank ? s.backward : s.forward;
        for (int i = 0; i < banks; i++) {
            half.reset();
            half.seed(topo.graph.bankNode(i), 0L);
            half.run(this);
            for (int k = 0; k < half.size(); k++) {
                int r = half.rankAt(k);
                labels[topo.bankSlot[r] * banks + i] = half.dist[r];
                preds[topo.bankSlot[r] * banks + i] = half.pred[r];
            }
        }
        return new BankLabels(labels, preds);
    }

    /**
     * Weighs {@code topo} for one class. {@code canUse} and {@code tCosts} index transports as
     * {@link ClusterGraph#transports} does. The metric keeps all three to price arcs again when a route
     * is unpacked, so none may change afterwards. {@code parallel} finalizes each level of the
     * elimination tree across the common pool.
     */
    static CchMetric customize(CchTopology topo, DangerField danger, boolean[] canUse, int[] tCosts, boolean parallel) {
        CchMetric m = new CchMetric(topo, danger, canUse, tCosts);
        for (int c = 0; c < ClusterGeometry.TOTAL_CLUSTERS; c++) {
            int from = topo.intraFirst[c];
            int to = topo.intraFirst[c + 1];
            if (from == to) {
                continue;
            }
            int[] w = danger.intraMatrix(topo.graph, c);
            int[] t = danger.intraMatrixTimes(topo.graph, c);
            for (int a = from; a < to; a++) {
                int cell = topo.intraCell[a];
                if (w[cell] != Integer.MAX_VALUE) {
                    m.offer(topo.intraSlot[a], pack(w[cell], t[cell]));
                }
            }
        }
        for (int a = topo.boundaryArcs; a < topo.arcCount(); a++) {
            long w = m.arcWeight(a);
            if (w != INF) {
                m.offer(topo.arcSlot(a), w);
            }
        }
        m.triangles(parallel);
        return m;
    }

    private void offer(int slot, long weight) {
        int e = slot >>> 1;
        if ((slot & 1) == 0) {
            if (weight < up[e]) {
                up[e] = weight;
            }
        } else if (weight < down[e]) {
            down[e] = weight;
        }
    }

    /** Input arc {@code a}'s packed weight: {@link #INF} for an unusable transport or an unreachable cell. */
    long arcWeight(int a) {
        CchTopology t = topo;
        int k = a - t.boundaryArcs;
        if (k < 0) {
            int c = t.intraClusterOf(a);
            int cell = t.intraCell[a];
            int w = danger.intraMatrix(t.graph, c)[cell];
            return w == Integer.MAX_VALUE ? INF : pack(w, danger.intraMatrixTimes(t.graph, c)[cell]);
        }
        if (k < t.boundarySlot.length) {
            return pack(Engine.STEP_COST, Engine.STEP_COST);
        }
        k -= t.boundarySlot.length;
        if (k < t.transportSlot.length) {
            int tIdx = t.transportIdx[k];
            return canUse[tIdx] ? landing(tCosts[tIdx], t.transportLanding[k]) : INF;
        }
        k -= t.transportSlot.length;
        if (k < t.anywhereSlot.length) {
            int tIdx = t.anywhereIdx[k];
            return canUse[tIdx] ? landing(tCosts[tIdx], t.anywhereLanding[k], Engine.TELEPORT_WEIGHT) : INF;
        }
        return 0L;
    }

    private long landing(int cost, int node) {
        return landing(cost, node, 0);
    }

    /** A hop's cost is its time; the landing's danger charge and the surcharge are weight only. */
    private long landing(int cost, int node, int surcharge) {
        ClusterGraph graph = topo.graph;
        return pack(cost + surcharge + danger.penaltyAt(graph.nodeX(node), graph.nodeY(node)), cost);
    }

    /**
     * How edge {@code e}'s weight toward its head, or its tail, was reached: the index of a lower
     * triangle in {@link CchTopology#triangles}, whose two edges are its halves, or {@code -(tag + 4)}
     * for an input arc ({@link #tagOf}). A function of the weights alone, so equal metrics unpack equal
     * routes, and what it names always adds up to exactly that weight and time.
     *
     * @throws IllegalStateException when nothing does
     */
    int via(int e, boolean toHead) {
        CchTopology t = topo;
        long w = toHead ? up[e] : down[e];
        int direction = toHead ? 0 : 1;
        for (int i = t.edgeArcFirst[e]; i < t.edgeArcFirst[e + 1]; i++) {
            int a = t.edgeArcs[i];
            if ((t.arcSlot(a) & 1) == direction && arcWeight(a) == w) {
                return -(t.arcTag(a) + 4);
            }
        }
        int[] triangles = t.triangles;
        for (int k = t.triangleFirst[e]; k < t.triangleFirst[e + 1]; k++) {
            int vx = triangles[2 * k];
            int vy = triangles[2 * k + 1];
            if ((toHead ? down[vx] + up[vy] : down[vy] + up[vx]) == w) {
                return k;
            }
        }
        throw new IllegalStateException("edge " + e + " has no arc or triangle for its weight");
    }

    private void triangles(boolean parallel) {
        int n = topo.nodeCount;
        if (!parallel) {
            int[] slotOf = new int[n];
            for (int x = 0; x < n; x++) {
                relaxLowerTriangles(x, slotOf);
            }
            return;
        }
        ThreadLocal<int[]> scratch = ThreadLocal.withInitial(() -> new int[n]);
        int[] serial = new int[n];
        for (int[] level : topo.levels) {
            if (level.length < 64) {
                for (int x : level) {
                    relaxLowerTriangles(x, serial);
                }
            } else {
                Parallel.forEach(level.length, i -> relaxLowerTriangles(level[i], scratch.get()));
            }
        }
    }

    /** Finalizes rank {@code x}'s upward edges; every edge read has a lower tail, so is final already. */
    private void relaxLowerTriangles(int x, int[] unused) {
        int[] firstUp = topo.firstUp;
        int[] triangleFirst = topo.triangleFirst;
        int[] triangles = topo.triangles;
        long[] up = this.up;
        long[] down = this.down;
        for (int xy = firstUp[x]; xy < firstUp[x + 1]; xy++) {
            long toHead = up[xy];
            long toTail = down[xy];
            for (int k = triangleFirst[xy]; k < triangleFirst[xy + 1]; k++) {
                int vx = triangles[2 * k];
                int vy = triangles[2 * k + 1];
                long viaHead = down[vx] + up[vy];
                if (viaHead < toHead) {
                    toHead = viaHead;
                }
                long viaTail = down[vy] + up[vx];
                if (viaTail < toTail) {
                    toTail = viaTail;
                }
            }
            up[xy] = toHead;
            down[xy] = toTail;
        }
    }
}
