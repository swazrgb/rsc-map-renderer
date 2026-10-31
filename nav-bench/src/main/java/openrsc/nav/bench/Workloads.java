package openrsc.nav.bench;

import static openrsc.nav.bench.Shape.CELLS;
import static openrsc.nav.bench.Shape.CLUSTER_NODES;
import static openrsc.nav.bench.Shape.UNREACHED;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * The storage traffic the benchmarks measure: pricing reads against settles and planes, and a
 * settle's writes. Each comes twice, over a view (any layout) and over {@link ArraySettled} or
 * {@link ArrayPlane} fields directly, and the two return the same number for the same data.
 */
final class Workloads {

    private Workloads() {}

    /**
     * Prices a tile against one settle: the best leg-1 cost to each of a cluster's nodes from
     * {@code srcFirst} plus the settle's distance there, against each anywhere-castable transport's
     * cost plus the distance at its landing. Ties go to the shorter time.
     */
    static long composeTile(SettledView s, int srcFirst, int[] leg1, int[] leg1Time, int[] anywhere, int[] tCosts) {
        long bestCost = Long.MAX_VALUE;
        long bestTime = Long.MAX_VALUE;
        int bestNode = -1;
        for (int m = 0; m < leg1.length; m++) {
            if (leg1[m] == UNREACHED) {
                continue;
            }
            int mid = s.dist(srcFirst + m);
            if (mid == UNREACHED) {
                continue;
            }
            long total = (long) leg1[m] + mid;
            long time = (long) leg1Time[m] + s.time(srcFirst + m);
            if (total < bestCost || (total == bestCost && time < bestTime)) {
                bestCost = total;
                bestTime = time;
                bestNode = srcFirst + m;
            }
        }
        for (int a = 0; a < anywhere.length; a++) {
            int v = anywhere[a];
            int mid = s.dist(v);
            if (mid == UNREACHED) {
                continue;
            }
            long total = (long) tCosts[a] + mid;
            long time = (long) tCosts[a] + s.time(v);
            if (total < bestCost || (total == bestCost && time < bestTime)) {
                bestCost = total;
                bestTime = time;
                bestNode = v;
            }
        }
        return mix(bestCost, bestTime, bestNode);
    }

    static long composeTileArrays(
            ArraySettled s, int srcFirst, int[] leg1, int[] leg1Time, int[] anywhere, int[] tCosts) {
        long bestCost = Long.MAX_VALUE;
        long bestTime = Long.MAX_VALUE;
        int bestNode = -1;
        for (int m = 0; m < leg1.length; m++) {
            if (leg1[m] == UNREACHED) {
                continue;
            }
            int mid = s.dist[srcFirst + m];
            if (mid == UNREACHED) {
                continue;
            }
            long total = (long) leg1[m] + mid;
            long time = (long) leg1Time[m] + s.time[srcFirst + m];
            if (total < bestCost || (total == bestCost && time < bestTime)) {
                bestCost = total;
                bestTime = time;
                bestNode = srcFirst + m;
            }
        }
        for (int a = 0; a < anywhere.length; a++) {
            int v = anywhere[a];
            int mid = s.dist[v];
            if (mid == UNREACHED) {
                continue;
            }
            long total = (long) tCosts[a] + mid;
            long time = (long) tCosts[a] + s.time[v];
            if (total < bestCost || (total == bestCost && time < bestTime)) {
                bestCost = total;
                bestTime = time;
                bestNode = v;
            }
        }
        return mix(bestCost, bestTime, bestNode);
    }

    /** {@link #composeTile} on the concrete class, so its loop only ever sees one receiver type. */
    static long composeTilePacked(
            PackedSettled s, int srcFirst, int[] leg1, int[] leg1Time, int[] anywhere, int[] tCosts) {
        long bestCost = Long.MAX_VALUE;
        long bestTime = Long.MAX_VALUE;
        int bestNode = -1;
        for (int m = 0; m < leg1.length; m++) {
            if (leg1[m] == UNREACHED) {
                continue;
            }
            int mid = s.dist(srcFirst + m);
            if (mid == UNREACHED) {
                continue;
            }
            long total = (long) leg1[m] + mid;
            long time = (long) leg1Time[m] + s.time(srcFirst + m);
            if (total < bestCost || (total == bestCost && time < bestTime)) {
                bestCost = total;
                bestTime = time;
                bestNode = srcFirst + m;
            }
        }
        for (int a = 0; a < anywhere.length; a++) {
            int v = anywhere[a];
            int mid = s.dist(v);
            if (mid == UNREACHED) {
                continue;
            }
            long total = (long) tCosts[a] + mid;
            long time = (long) tCosts[a] + s.time(v);
            if (total < bestCost || (total == bestCost && time < bestTime)) {
                bestCost = total;
                bestTime = time;
                bestNode = v;
            }
        }
        return mix(bestCost, bestTime, bestNode);
    }

    /**
     * Prices one node against {@code goalSeed.length} settles from {@code first}: the least distance
     * at {@code node} plus that settle's seed.
     */
    static long composeFromNode(SettledView[] pool, int first, int node, int[] goalSeed) {
        long bestCost = Long.MAX_VALUE;
        long bestTime = Long.MAX_VALUE;
        int bestLocal = -1;
        for (int j = 0; j < goalSeed.length; j++) {
            if (goalSeed[j] == UNREACHED) {
                continue;
            }
            SettledView s = pool[first + j];
            int mid = s.dist(node);
            if (mid == UNREACHED) {
                continue;
            }
            long total = (long) mid + goalSeed[j];
            long time = s.time(node);
            if (total < bestCost || (total == bestCost && time < bestTime)) {
                bestCost = total;
                bestTime = time;
                bestLocal = j;
            }
        }
        return mix(bestCost, bestTime, bestLocal);
    }

    static long composeFromNodeArrays(ArraySettled[] pool, int first, int node, int[] goalSeed) {
        long bestCost = Long.MAX_VALUE;
        long bestTime = Long.MAX_VALUE;
        int bestLocal = -1;
        for (int j = 0; j < goalSeed.length; j++) {
            if (goalSeed[j] == UNREACHED) {
                continue;
            }
            ArraySettled s = pool[first + j];
            int mid = s.dist[node];
            if (mid == UNREACHED) {
                continue;
            }
            long total = (long) mid + goalSeed[j];
            long time = s.time[node];
            if (total < bestCost || (total == bestCost && time < bestTime)) {
                bestCost = total;
                bestTime = time;
                bestLocal = j;
            }
        }
        return mix(bestCost, bestTime, bestLocal);
    }

    /**
     * A settle's store traffic: clear, then {@link Queries#EDGES} relaxations by distance, then time.
     * Returns how many relaxations wrote.
     */
    static int settle(SettledView s, Queries q) {
        s.clear();
        int writes = 0;
        for (int e = 0; e < Queries.EDGES; e++) {
            int v = q.edgeNode[e];
            int nd = q.edgeDist[e];
            int nt = q.edgeTime[e];
            int d = s.dist(v);
            if (nd < d || (nd == d && nt < s.time(v))) {
                s.set(v, nd, nt, q.edgeFrom[e], q.edgeTag[e]);
                writes++;
            }
        }
        return writes;
    }

    static int settleArrays(ArraySettled s, Queries q) {
        Arrays.fill(s.dist, UNREACHED);
        Arrays.fill(s.time, UNREACHED);
        Arrays.fill(s.parent, (short) -1);
        Arrays.fill(s.tag, (short) 0);
        int writes = 0;
        for (int e = 0; e < Queries.EDGES; e++) {
            int v = q.edgeNode[e];
            int nd = q.edgeDist[e];
            int nt = q.edgeTime[e];
            int d = s.dist[v];
            if (nd < d || (nd == d && nt < s.time[v])) {
                s.dist[v] = nd;
                s.time[v] = nt;
                s.parent[v] = q.edgeFrom[e];
                s.tag[v] = q.edgeTag[e];
                writes++;
            }
        }
        return writes;
    }

    /**
     * Every node's cost and time at the tile {@code cells[at]}, lowered to the least over the next
     * {@code count - 1} tiles of the stream.
     */
    static int costsAt(PlaneView p, int[] cells, int at, int count, int[] out, int[] outTimes) {
        int c0 = cells[at & Queries.MASK];
        for (int i = 0; i < CLUSTER_NODES; i++) {
            out[i] = p.cost(i, c0);
            outTimes[i] = p.time(i, c0);
        }
        for (int t = 1; t < count; t++) {
            int c = cells[(at + t) & Queries.MASK];
            for (int i = 0; i < CLUSTER_NODES; i++) {
                int v = p.cost(i, c);
                if (v < out[i]) {
                    out[i] = v;
                    outTimes[i] = p.time(i, c);
                }
            }
        }
        return out[0] ^ outTimes[CLUSTER_NODES - 1];
    }

    static int costsAtArrays(ArrayPlane p, int[] cells, int at, int count, int[] out, int[] outTimes) {
        int c0 = cells[at & Queries.MASK];
        for (int i = 0; i < CLUSTER_NODES; i++) {
            out[i] = p.grids[i * CELLS + c0];
            outTimes[i] = p.times[i * CELLS + c0];
        }
        for (int t = 1; t < count; t++) {
            int c = cells[(at + t) & Queries.MASK];
            for (int i = 0; i < CLUSTER_NODES; i++) {
                int v = p.grids[i * CELLS + c];
                if (v < out[i]) {
                    out[i] = v;
                    outTimes[i] = p.times[i * CELLS + c];
                }
            }
        }
        return out[0] ^ outTimes[CLUSTER_NODES - 1];
    }

    /** Fills deterministically per seed and entry, so every layout holds the same numbers. */
    static void fill(SettledView s, long seed, int entry) {
        SplittableRandom r = new SplittableRandom(seed * 1_000_003L + entry);
        for (int n = 0; n < Shape.NODES; n++) {
            boolean reached = r.nextInt(20) != 0;
            int dist = reached ? r.nextInt(1_000_000) : UNREACHED;
            int time = reached ? dist / 2 + r.nextInt(1_000) : UNREACHED;
            short parent = (short) (r.nextInt(8) == 0 ? -1 : r.nextInt(Shape.NODES));
            s.set(n, dist, time, parent, (short) r.nextInt(2229));
        }
    }

    static void fill(PlaneView p, long seed, int entry) {
        SplittableRandom r = new SplittableRandom(seed * 1_000_003L + entry);
        for (int node = 0; node < CLUSTER_NODES; node++) {
            for (int cell = 0; cell < CELLS; cell++) {
                boolean reached = r.nextInt(20) != 0;
                int cost = reached ? r.nextInt(100_000) : UNREACHED;
                p.set(node, cell, cost, reached ? cost / 2 + r.nextInt(100) : UNREACHED);
            }
        }
    }

    private static long mix(long cost, long time, int node) {
        return cost * 31 + time * 17 + node;
    }
}
