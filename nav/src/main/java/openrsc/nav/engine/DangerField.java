package openrsc.nav.engine;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * One danger class's view of the {@link DangerMap}: its gate applied at one rung and its base scaled
 * by one weight. A tile is charged its base times the scale where the rung's level is below the
 * tile's ceiling, else nothing ({@link #penaltyAt}); charges are weight in deci-tick units, never
 * time. Every bot on a rung shares one view, and every cache keyed on its {@link #signature}.
 * Immutable but for the intra matrices it builds on demand; safe for concurrent use.
 */
final class DangerField {

    /** Bumped when a change here can change a persisted plane: a charge, a matrix, a signature. */
    static final int PLANE_VERSION = 2;

    private static final int SCALE_BITS = 11;

    private final DangerMap map;

    private final int gateCl;
    private final int scale;
    private final int signature;
    private final int[] clusterPenalty;
    private final boolean[] charges;

    private final boolean anyDanger;
    private final int[] viewSig;

    private final AtomicReferenceArray<int[]> dangerIntra;
    private final AtomicReferenceArray<int[]> dangerIntraTimes;

    /** {@code weight} is clamped to [0, 1000]; 0 charges nothing. */
    DangerField(DangerMap map, int rung, int weight) {
        this.map = map;

        this.gateCl = map.representativeCl(rung);
        this.scale = clampWeight(weight);
        this.signature = (rung + 1) << SCALE_BITS | this.scale;
        int total = map.clusterCount();
        int[] pen = new int[total];
        boolean[] charging = new boolean[total];
        boolean any = false;
        if (this.scale != 0 && map.any()) {
            int cs = ClusterGeometry.CLUSTER_SIZE;
            for (int c = 0; c < total; c++) {
                if (!map.clusterHasDanger(c)) {
                    continue;
                }
                long sum = 0;
                for (int i = 0; i < cs * cs; i++) {
                    if (gateCl < map.ceilingAt(c, i)) {
                        sum += map.rawBaseAt(c, i);
                    }
                }
                if (sum > 0) {
                    charging[c] = true;
                    pen[c] = (int) Math.min(DangerMap.CLUSTER_CAP, sum / DangerMap.CLUSTER_DIVISOR);
                    any = true;
                }
            }
        }
        this.clusterPenalty = pen;
        this.charges = charging;
        this.anyDanger = any;
        this.viewSig = new int[total];
        for (int c = 0; c < total; c++) {
            if (pen[c] != 0) {
                viewSig[c] = (map.viewOf(c, gateCl) + 1) << SCALE_BITS | this.scale;
            }
        }
        this.dangerIntra = any ? new AtomicReferenceArray<>(ClusterGeometry.TOTAL_CLUSTERS) : null;
        this.dangerIntraTimes = any ? new AtomicReferenceArray<>(ClusterGeometry.TOTAL_CLUSTERS) : null;
    }

    /** The danger class as a key: (rung, scale), the same in every run, and never 0, which keys use for none. */
    int signature() {
        return signature;
    }

    /**
     * What this field charges in one cluster, as a key: 0 where {@link #clusterPenalty} is 0, else the
     * cluster's view ({@link DangerMap#viewOf}) and the scale. Per-cluster planes and matrices key on it,
     * so rungs that see a cluster alike share them.
     */
    int viewSignature(int cluster) {
        return anyDanger ? viewSig[cluster] : 0;
    }

    boolean anyDanger() {
        return anyDanger;
    }

    /** {@link #penaltyAt} for every tile of the {@code width x height} window at {@code (x0, y0)}, row-major. */
    void penaltiesInto(int x0, int y0, int width, int height, int[] out) {
        int lastClusterX = (ClusterGeometry.CLUSTERS_X - 1) * ClusterGeometry.CLUSTER_SIZE;
        for (int r = 0; r < height; r++) {
            int y = y0 + r;
            int row = r * width;
            int end = x0 + width;
            int x = x0;
            while (x < end) {
                int spanEnd = x < 0
                        ? Math.min(end, 0)
                        : x >= lastClusterX
                                ? end
                                : Math.min(end, (x / ClusterGeometry.CLUSTER_SIZE + 1) * ClusterGeometry.CLUSTER_SIZE);
                if (!anyDanger || !charges[ClusterGeometry.clusterOf(x, y)]) {
                    Arrays.fill(out, row + (x - x0), row + (spanEnd - x0), 0);
                } else {
                    for (int tx = x; tx < spanEnd; tx++) {
                        out[row + (tx - x0)] = penaltyAt(tx, y);
                    }
                }
                x = spanEnd;
            }
        }
    }

    /**
     * Whether any tile of the inclusive rectangle may be charged: false guarantees {@link #penaltyAt} is
     * 0 on every one. Answered per cluster, so true may only mean a cluster it touches charges elsewhere.
     */
    boolean chargesAnyIn(int x0, int y0, int x1, int y1) {
        if (!anyDanger) {
            return false;
        }
        // Every cluster is at least 48 tiles a side, so a 32-tile stride and the far edge land in each.
        for (int y = y0; ; y = Math.min(y + 32, y1)) {
            for (int x = x0; ; x = Math.min(x + 32, x1)) {
                if (charges[ClusterGeometry.clusterOf(x, y)]) {
                    return true;
                }
                if (x == x1) {
                    break;
                }
            }
            if (y == y1) {
                break;
            }
        }
        return false;
    }

    int penaltyAt(int x, int y) {
        if (!anyDanger) {
            return 0;
        }
        int c = ClusterGeometry.clusterOf(x, y);
        if (!map.clusterHasDanger(c)) {
            return 0;
        }
        int idx = (y - ClusterGeometry.minY(c)) * ClusterGeometry.CLUSTER_SIZE + (x - ClusterGeometry.minX(c));
        if (gateCl >= map.ceilingAt(c, idx)) {
            return 0;
        }
        return map.rawBaseAt(c, idx) * scale;
    }

    /** {@link #penaltyAt} before the scale. */
    int rawPenaltyAt(int x, int y) {
        if (!anyDanger) {
            return 0;
        }
        int c = ClusterGeometry.clusterOf(x, y);
        if (!map.clusterHasDanger(c)) {
            return 0;
        }
        int idx = (y - ClusterGeometry.minY(c)) * ClusterGeometry.CLUSTER_SIZE + (x - ClusterGeometry.minX(c));
        return gateCl >= map.ceilingAt(c, idx) ? 0 : map.rawBaseAt(c, idx);
    }

    /**
     * The cluster's charged tiles' base summed, over {@link DangerMap#CLUSTER_DIVISOR} and at most
     * {@link DangerMap#CLUSTER_CAP}, times the scale. 0 for a cluster charged so lightly that the
     * division rounds it away, whose tiles {@link #penaltyAt} still charges.
     */
    int clusterPenalty(int clusterId) {
        return anyDanger ? clusterPenalty[clusterId] * scale : 0;
    }

    /**
     * The map's base cells for the cluster, or null when this field charges nothing anywhere. With
     * {@link #ceilCells}: a tile's charge is {@code base[i] * scale()} where {@code ceil[i] > gate()}.
     */
    short[] baseCells(int cluster) {
        return anyDanger ? map.baseCells(cluster) : null;
    }

    short[] ceilCells(int cluster) {
        return anyDanger ? map.ceilCells(cluster) : null;
    }

    /** The rung's representative combat level. */
    int gate() {
        return gateCl;
    }

    int scale() {
        return scale;
    }

    /**
     * The intra-cluster matrix to price the cluster with: danger-aware where {@link #clusterPenalty} is
     * not 0, else the graph's plain one ({@link ClusterGraph#intraMatrix}).
     */
    int[] intraMatrix(ClusterGraph graph, int cluster) {
        if (dangerIntra == null || clusterPenalty[cluster] == 0) {
            return graph.intraMatrix(cluster);
        }
        int[] m = dangerIntra.get(cluster);
        if (m == null) {
            buildIntra(graph, cluster);
            m = dangerIntra.get(cluster);
        }
        return m;
    }

    /** Each path's time in {@link #intraMatrix}, its charge left out; the same array where nothing is charged. */
    int[] intraMatrixTimes(ClusterGraph graph, int cluster) {
        if (dangerIntra == null || clusterPenalty[cluster] == 0) {
            return graph.intraMatrix(cluster);
        }
        int[] t = dangerIntraTimes.get(cluster);
        if (t == null) {
            buildIntra(graph, cluster);
            t = dangerIntraTimes.get(cluster);
        }
        return t;
    }

    private void buildIntra(ClusterGraph graph, int cluster) {
        int[][] both = map.viewMatrices(graph, this, cluster);
        dangerIntraTimes.set(cluster, both[1]);
        dangerIntra.set(cluster, both[0]);
    }

    /** Whether a charged tile lies in the inclusive window: tile-exact, unlike {@link #chargesAnyIn}. */
    boolean intersects(int xMin, int yMin, int xMax, int yMax) {
        if (!anyDanger) {
            return false;
        }
        int cs = ClusterGeometry.CLUSTER_SIZE;
        xMin = Math.max(0, xMin);
        yMin = Math.max(0, yMin);
        for (int y = yMin; y <= yMax; y = (y / cs + 1) * cs) {
            for (int x = xMin; x <= xMax; x = (x / cs + 1) * cs) {
                int c = ClusterGeometry.clusterOf(x, y);
                if (c >= 0
                        && c < map.clusterCount()
                        && map.clusterHasDanger(c)
                        && hasDangerInWindow(c, xMin, yMin, xMax, yMax)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasDangerInWindow(int c, int xMin, int yMin, int xMax, int yMax) {
        int cMinX = ClusterGeometry.minX(c);
        int cMinY = ClusterGeometry.minY(c);
        int cs = ClusterGeometry.CLUSTER_SIZE;
        int x0 = Math.max(xMin, cMinX);
        int x1 = Math.min(xMax, cMinX + cs - 1);
        int y0 = Math.max(yMin, cMinY);
        int y1 = Math.min(yMax, cMinY + cs - 1);
        for (int y = y0; y <= y1; y++) {
            int base = (y - cMinY) * cs;
            for (int x = x0; x <= x1; x++) {
                int idx = base + (x - cMinX);
                if (map.rawBaseAt(c, idx) != 0 && gateCl < map.ceilingAt(c, idx)) {
                    return true;
                }
            }
        }
        return false;
    }

    static int clampWeight(int weight) {
        return Math.max(0, Math.min(1000, weight));
    }
}
