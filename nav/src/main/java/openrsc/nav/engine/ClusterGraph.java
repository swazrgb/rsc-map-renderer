package openrsc.nav.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.TeleportRegion;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.BankSite;
import openrsc.nav.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The static graph the hierarchy routes over: a few nodes per cluster ({@link ClusterGeometry}) and
 * the arcs between them, from the collision map, the transports and the banks alone. Which
 * transports an account may use is decided at query time, so one graph serves every account.
 *
 * <p>Nodes are tiles, numbered so that each cluster's are one contiguous range. On each side of
 * every run of passable boundary between neighbouring clusters there is an entrance, one per
 * stretch of the run along which the walkable component on neither side changes; every transport
 * entry and landing and every bank tile is a portal. Arcs are the walks inside a cluster
 * ({@link #intraMatrix}), single steps across a boundary ({@link #outBoundary}) and transports
 * ({@link #outTransports}). A transport with no entry tiles is used from anywhere in its region and
 * has only a landing ({@link #anywhereDestNode}). Weights are deci-ticks.
 *
 * <p>Immutable and safe to share.
 */
public final class ClusterGraph {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterGraph.class);

    private static final int[] EMPTY_INT = new int[0];

    private final CollisionMap map;
    private final List<Transport> transports;

    private final int[] clusterFirstNode;
    private final int totalNodes;
    private final int[] nodeX;

    private final int[] nodeY;
    private final int[] nodeCluster;
    private final int[] nodeSpanLo;

    private final int[] nodeSpanHi;
    private final int[][] clusterMatrix;

    private final int[] boundaryA;

    private final int[] boundaryB;
    private final int boundaryEdgeCount;

    private final int[] transportFrom;

    private final int[] transportTo;
    private final int[] transportIdx;
    private final int transportEdgeCount;

    private final int[] anywhereDestNode;

    private final int[] anywhereTransportIdx;
    private final int[] anywhereSource;

    private final TeleportRegion[] sourceRegion;

    private final int[] castEntryFirst;

    private final int[] castEntryNodes;

    private final int[] bankNodes;

    private final int[][] outTransports;

    private final int[][] outBoundary;

    private final AtomicReferenceArray<ClusterEdges> edgeTables;

    private ClusterGraph(
            CollisionMap map,
            List<Transport> transports,
            int[] clusterFirstNode,
            int totalNodes,
            int[] nodeX,
            int[] nodeY,
            int[] nodeCluster,
            int[] nodeSpanLo,
            int[] nodeSpanHi,
            int[][] clusterMatrix,
            int[] boundaryA,
            int[] boundaryB,
            int boundaryEdgeCount,
            int[] transportFrom,
            int[] transportTo,
            int[] transportIdx,
            int transportEdgeCount,
            int[] anywhereDestNode,
            int[] anywhereTransportIdx,
            int[] anywhereSource,
            TeleportRegion[] sourceRegion,
            int[] castEntryFirst,
            int[] castEntryNodes,
            int[] bankNodes,
            int[][] outTransports,
            int[][] outBoundary,
            AtomicReferenceArray<ClusterEdges> edgeTables) {
        this.map = map;
        this.edgeTables = edgeTables;
        this.transports = transports;
        this.clusterFirstNode = clusterFirstNode;
        this.totalNodes = totalNodes;
        this.nodeX = nodeX;
        this.nodeY = nodeY;
        this.nodeCluster = nodeCluster;
        this.nodeSpanLo = nodeSpanLo;
        this.nodeSpanHi = nodeSpanHi;
        this.clusterMatrix = clusterMatrix;
        this.boundaryA = boundaryA;
        this.boundaryB = boundaryB;
        this.boundaryEdgeCount = boundaryEdgeCount;
        this.transportFrom = transportFrom;
        this.transportTo = transportTo;
        this.transportIdx = transportIdx;
        this.transportEdgeCount = transportEdgeCount;
        this.anywhereDestNode = anywhereDestNode;
        this.anywhereTransportIdx = anywhereTransportIdx;
        this.anywhereSource = anywhereSource;
        this.sourceRegion = sourceRegion;
        this.castEntryFirst = castEntryFirst;
        this.castEntryNodes = castEntryNodes;
        this.bankNodes = bankNodes;
        this.outTransports = outTransports;
        this.outBoundary = outBoundary;
    }

    /** Feeds every structural array into a store stamp; the per-node adjacency is derived from them. */
    void digest(java.security.MessageDigest md) {
        Digests.ints(md, clusterFirstNode);
        Digests.ints(md, new int[] {totalNodes, boundaryEdgeCount, transportEdgeCount});
        Digests.ints(md, nodeX);
        Digests.ints(md, nodeY);
        Digests.ints(md, nodeCluster);
        Digests.ints(md, nodeSpanLo);
        Digests.ints(md, nodeSpanHi);
        for (int[] matrix : clusterMatrix) {
            Digests.ints(md, matrix);
        }
        Digests.ints(md, boundaryA);
        Digests.ints(md, boundaryB);
        Digests.ints(md, transportFrom);
        Digests.ints(md, transportTo);
        Digests.ints(md, transportIdx);
        Digests.ints(md, anywhereDestNode);
        Digests.ints(md, anywhereTransportIdx);
        Digests.ints(md, anywhereSource);
        Digests.ints(md, castEntryFirst);
        Digests.ints(md, castEntryNodes);
        Digests.ints(md, bankNodes);
    }

    /**
     * The graph of these inputs, built once and shared while anything holds it. The inputs are compared
     * by identity: a list assembled anew gets a graph of its own, so a narrowed transport list is never
     * answered with the full one's. Takes seconds on the full map; thread-safe.
     */
    public static ClusterGraph build(CollisionMap map, List<Transport> transports, List<BankSite> banks) {
        synchronized (BUILT) {
            for (Iterator<Built> it = BUILT.iterator(); it.hasNext(); ) {
                Built b = it.next();
                ClusterGraph g = b.graph().get();
                if (g == null) {
                    it.remove();
                } else if (g.map == map
                        && g.transports == transports
                        && b.banks().get() == banks) {
                    return g;
                }
            }
            ClusterGraph g = buildUncached(map, transports, banks);
            BUILT.add(new Built(new WeakReference<>(g), new WeakReference<>(banks)));
            return g;
        }
    }

    private record Built(WeakReference<ClusterGraph> graph, WeakReference<List<BankSite>> banks) {}

    private static final List<Built> BUILT = new ArrayList<>();

    private static ClusterGraph buildUncached(CollisionMap map, List<Transport> transports, List<BankSite> banks) {
        long t0 = System.nanoTime();
        Builder b = new Builder(map, transports, banks);
        b.computeIntraClusterComponents();
        b.enumerateEntrances();
        b.enumeratePortals();
        b.enumerateBlockedLandingEscapes();
        b.assignNodeIds();
        b.computeIntraClusterMatrices();
        b.buildBoundaryEdges();
        b.buildTransportEdges();
        b.buildCastSources();
        ClusterGraph g = b.toGraph();
        LOG.info(
                "nav: cluster graph built in {}ms — {} nodes, {} boundary pairs, {} transport edges, {} teleports from {} cast sources ({} cast entries)",
                (System.nanoTime() - t0) / 1_000_000,
                g.totalNodes,
                g.boundaryEdgeCount,
                g.transportEdgeCount,
                g.anywhereDestNode.length,
                g.sourceRegion.length,
                g.castEntryNodes.length);
        return g;
    }

    /** {@code cluster}'s step masks, baked on first ask and kept. */
    ClusterEdges edges(int cluster) {
        return edges(map, edgeTables, cluster);
    }

    private static ClusterEdges edges(CollisionMap map, AtomicReferenceArray<ClusterEdges> tables, int cluster) {
        ClusterEdges e = tables.get(cluster);
        if (e != null) {
            return e;
        }
        ClusterEdges built = ClusterEdges.build(map, cluster);
        return tables.compareAndSet(cluster, null, built) ? built : tables.get(cluster);
    }

    public int totalNodes() {
        return totalNodes;
    }

    public int nodeX(int nodeId) {
        return nodeX[nodeId];
    }

    public int nodeY(int nodeId) {
        return nodeY[nodeId];
    }

    public int nodeCluster(int nodeId) {
        return nodeCluster[nodeId];
    }

    /**
     * One end of the node's span, as {@code y * WIDTH + x}: for an entrance, the run of boundary tiles
     * it stands for, any of which crosses as well as its own; for any other node, its own tile.
     */
    public int nodeSpanLo(int nodeId) {
        return nodeSpanLo[nodeId];
    }

    /** The other end of {@link #nodeSpanLo}'s span. */
    public int nodeSpanHi(int nodeId) {
        return nodeSpanHi[nodeId];
    }

    /** The cluster's first node: its nodes are the {@link #clusterNodeCount} ids from this one. */
    public int clusterFirstNode(int clusterId) {
        return clusterFirstNode[clusterId];
    }

    public int clusterNodeCount(int clusterId) {
        return clusterFirstNode[clusterId + 1] - clusterFirstNode[clusterId];
    }

    /**
     * The cluster's walking costs between its nodes, row-major {@code n x n} by local index (node id
     * less {@link #clusterFirstNode}), {@link Integer#MAX_VALUE} where no walk inside the cluster joins
     * them; null for a cluster with no nodes. Shared: never write it.
     */
    public int[] intraMatrix(int clusterId) {
        return clusterMatrix[clusterId];
    }

    /** One cell of {@link #intraMatrix}; {@link Integer#MAX_VALUE} also for a cluster with no nodes. */
    public int intraCost(int clusterId, int a, int b) {
        int[] m = clusterMatrix[clusterId];
        if (m == null) {
            return Integer.MAX_VALUE;
        }
        int n = clusterNodeCount(clusterId);
        return m[a * n + b];
    }

    static final int STEP_COST = Legs.STEP_COST;

    /** {@link #computeDangerAwareMatrix} that also fills {@code outTimes}, when not null, with each path's time. */
    public int[] computeDangerAwareMatrix(int c, DangerField danger, int[] outTimes) {
        int n = clusterNodeCount(c);
        if (n == 0) {
            return new int[0];
        }
        if (n == 1) {
            if (outTimes != null && outTimes.length > 0) {
                outTimes[0] = 0;
            }
            return new int[] {0};
        }
        int xMin = ClusterGeometry.minX(c);

        int yMin = ClusterGeometry.minY(c);

        int w = ClusterGeometry.CLUSTER_SIZE;
        int cellCount = w * w;
        int[] result = new int[n * n];
        Arrays.fill(result, Integer.MAX_VALUE);
        int firstNode = clusterFirstNode[c];
        int[][] spanLocals = new int[n][];
        for (int i = 0; i < n; i++) {
            spanLocals[i] = spanLocalIds(firstNode + i, xMin, yMin, w);
        }
        ClusterEdges edges = edges(c);
        int[] penalty = new int[cellCount];
        for (int l = 0; l < cellCount; l++) {
            if (edges.in[l] != 0) {
                penalty[l] = danger.penaltyAt(xMin + l % w, yMin + l / w);
            }
        }
        int[] dist = new int[cellCount];
        int[] time = outTimes == null ? null : new int[cellCount];
        long[] longHeap = new long[cellCount * 8];
        for (int src = 0; src < n; src++) {
            Arrays.fill(dist, Integer.MAX_VALUE);
            if (time != null) {
                Arrays.fill(time, Integer.MAX_VALUE);
            }
            int heapSize = 0;
            for (int sLocal : spanLocals[src]) {
                if (dist[sLocal] == Integer.MAX_VALUE) {
                    dist[sLocal] = 0;
                    if (time != null) {
                        time[sLocal] = 0;
                    }
                    longHeap[heapSize++] = Builder.packDijkstraKey(0, sLocal);
                }
            }
            if (heapSize == 0) {
                result[src * n + src] = 0;
                continue;
            }
            while (heapSize > 0) {
                long top = longHeap[0];
                int cost = (int) (top >>> 32);
                int local = (int) top;
                heapSize--;
                if (heapSize > 0) {
                    longHeap[0] = longHeap[heapSize];
                    Builder.siftDown(longHeap, heapSize, 0);
                }
                if (cost > dist[local]) {
                    continue;
                }
                for (int m = edges.out[local]; m != 0; m &= m - 1) {
                    int nLocal = local + ClusterEdges.STEP[Integer.numberOfTrailingZeros(m)];
                    int nCost = cost + STEP_COST + penalty[nLocal];
                    if (nCost < dist[nLocal]) {
                        dist[nLocal] = nCost;
                        if (time != null) {
                            time[nLocal] = time[local] + STEP_COST;
                        }
                        longHeap[heapSize] = Builder.packDijkstraKey(nCost, nLocal);
                        Builder.siftUp(longHeap, heapSize);
                        heapSize++;
                    }
                }
            }
            for (int dst = 0; dst < n; dst++) {
                if (dst == src) {
                    result[src * n + src] = 0;
                    if (outTimes != null) {
                        outTimes[src * n + src] = 0;
                    }
                    continue;
                }
                int best = Integer.MAX_VALUE;
                int bestTime = Integer.MAX_VALUE;
                for (int dLocal : spanLocals[dst]) {
                    int d = dist[dLocal];
                    if (d < best) {
                        best = d;
                        bestTime = time == null ? d : time[dLocal];
                    }
                }
                if (best != Integer.MAX_VALUE) {
                    // The plain distance is a floor under any charged one; seeding a whole span at 0 can read below it.
                    int base = intraCost(c, src, dst);
                    result[src * n + dst] = Math.max(best, base);
                    bestTime = base == Integer.MAX_VALUE ? bestTime : Math.max(bestTime, base);
                }
                if (outTimes != null) {
                    outTimes[src * n + dst] = bestTime;
                }
            }
        }
        return result;
    }

    public int anywhereCount() {
        return anywhereDestNode.length;
    }

    /** The landing node of teleport {@code i} of the {@link #anywhereCount} used from anywhere in a region. */
    public int anywhereDestNode(int i) {
        return anywhereDestNode[i];
    }

    /** Teleport {@code i}'s index into {@link #transports}. */
    public int anywhereTransportIdx(int i) {
        return anywhereTransportIdx[i];
    }

    /** Teleport {@code i}'s cast source: one per distinct region, shared by every teleport of it. */
    public int anywhereSource(int i) {
        return anywhereSource[i];
    }

    public int castSourceCount() {
        return sourceRegion.length;
    }

    /** The region cast source {@code s}'s teleports are used from. */
    public TeleportRegion castSourceRegion(int s) {
        return sourceRegion[s];
    }

    /** The nodes a route may cast from part way through, for cast source {@code s}; a copy. */
    public int[] castEntryNodes(int s) {
        return Arrays.copyOfRange(castEntryNodes, castEntryFirst[s], castEntryFirst[s + 1]);
    }

    public int bankNodeCount() {
        return bankNodes.length;
    }

    /** Bank {@code i}'s node, in the order the banks were given, less any off the map. */
    public int bankNode(int i) {
        return bankNodes[i];
    }

    /** Every transport leaving the node, flat as {@code [transportIdx, destNode, ...]}; never null. */
    public int[] outTransports(int nodeId) {
        return outTransports[nodeId];
    }

    /**
     * Every node one step from this one across a cluster boundary, and from a blocked transport landing
     * its open cardinal neighbours. Each such step weighs {@link Legs#STEP_COST}; never null.
     */
    public int[] outBoundary(int nodeId) {
        return outBoundary[nodeId];
    }

    /** The list the graph was built from, which every transport index indexes. */
    public List<Transport> transports() {
        return transports;
    }

    private int[] spanLocalIds(int node, int xMin, int yMin, int cs) {
        int mw = CollisionMap.WIDTH;
        int lo = nodeSpanLo[node], hi = nodeSpanHi[node];
        int x0 = Math.min(lo % mw, hi % mw), x1 = Math.max(lo % mw, hi % mw);
        int y0 = Math.min(lo / mw, hi / mw), y1 = Math.max(lo / mw, hi / mw);
        int[] out = new int[(x1 - x0 + 1) * (y1 - y0 + 1)];
        int k = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int l = Builder.localId(x, y, xMin, yMin, cs);
                if (l >= 0) {
                    out[k++] = l;
                }
            }
        }
        return k == out.length ? out : Arrays.copyOf(out, k);
    }

    private static final class Builder {

        final CollisionMap map;
        final List<Transport> transports;

        final int[][] entrancesByCluster = new int[ClusterGeometry.TOTAL_CLUSTERS][];

        final int[][] portalsByCluster = new int[ClusterGeometry.TOTAL_CLUSTERS][];
        final HashMap<Integer, int[]> spanByTile = new HashMap<>();

        final List<long[]> rawBoundaryPairs = new ArrayList<>();

        final List<long[]> rawEscapePairs = new ArrayList<>();

        final HashMap<Integer, Integer> tileToNode = new HashMap<>();

        int[] clusterFirstNode;
        int totalNodes;
        int[] nodeX, nodeY, nodeCluster;
        int[] nodeSpanLo, nodeSpanHi;
        int[][] clusterMatrix;
        final AtomicReferenceArray<ClusterEdges> edgeTables =
                new AtomicReferenceArray<>(ClusterGeometry.TOTAL_CLUSTERS);
        int[] boundaryA, boundaryB;
        int boundaryEdgeCount;
        int[] transportFrom, transportTo, transportIdx;
        int transportEdgeCount;
        int[] anywhereDestNode, anywhereTransportIdx, anywhereSource;
        TeleportRegion[] sourceRegion;
        int[] castEntryFirst, castEntryNodes;
        int[] bankNodes;
        int[][] outTransports;
        int[][] outBoundary;
        int[] escapeFromNode, escapeToNode;
        int escapeEdgeCount;

        byte[][] intraComp;

        /**
         * Transports with no entry tiles that are not {@link Transport#entryFree()}: broken entry data, not
         * a teleport. Treated as one it would carry every account from every tile, so it is left out.
         */
        final Set<Transport> brokenEntryless = Collections.newSetFromMap(new IdentityHashMap<>());

        final List<BankSite> banks;

        Builder(CollisionMap map, List<Transport> transports, List<BankSite> banks) {
            this.banks = banks;
            this.map = map;
            this.transports = transports;
            for (Transport t : transports) {
                if (t.entries().isEmpty() && !t.entryFree()) {
                    brokenEntryless.add(t);
                    LOG.error(
                            "ClusterGraph: transport \"{}\" has NO entry tiles but is not entryFree() — "
                                    + "its entry enumeration is broken (blocked/mis-modelled entrance). Dropping it from "
                                    + "the graph instead of promoting it to an anywhere teleport.",
                            t.name());
                }
            }
        }

        void computeIntraClusterComponents() {
            int cs = ClusterGeometry.CLUSTER_SIZE;
            int cellCount = cs * cs;
            int[] queue = new int[cellCount];
            intraComp = new byte[ClusterGeometry.TOTAL_CLUSTERS][];
            for (int c = 0; c < ClusterGeometry.TOTAL_CLUSTERS; c++) {
                int xMin = ClusterGeometry.minX(c);
                int yMin = ClusterGeometry.minY(c);
                int xMax = ClusterGeometry.maxX(c);
                int yMax = ClusterGeometry.maxY(c);
                byte[] comp = new byte[cellCount];
                intraComp[c] = comp;
                int nextId = 0;
                for (int local = 0; local < cellCount; local++) {
                    if (comp[local] != 0) {
                        continue;
                    }
                    int sx = xMin + local % cs;
                    int sy = yMin + local / cs;
                    if (sx >= xMax || sy >= yMax) {
                        continue;
                    }
                    if (!map.inBounds(sx, sy) || map.fullyBlocked(sx, sy)) {
                        continue;
                    }
                    // A byte holds 127 ids: components past that stay unlabelled.
                    if (nextId == 127) {
                        continue;
                    }
                    nextId++;
                    comp[local] = (byte) nextId;
                    int head = 0, tail = 0;
                    queue[tail++] = local;
                    while (head < tail) {
                        int cur = queue[head++];
                        int cx = xMin + cur % cs;
                        int cy = yMin + cur / cs;
                        for (int dy = -1; dy <= 1; dy++) {
                            int ny = cy + dy;
                            if (ny < yMin || ny >= yMax) {
                                continue;
                            }
                            for (int dx = -1; dx <= 1; dx++) {
                                if (dx == 0 && dy == 0) {
                                    continue;
                                }
                                int nx = cx + dx;
                                if (nx < xMin || nx >= xMax) {
                                    continue;
                                }
                                int nLocal = (ny - yMin) * cs + (nx - xMin);
                                if (comp[nLocal] != 0) {
                                    continue;
                                }
                                if (!map.canStep(cx, cy, nx, ny, Overlays.NONE)) {
                                    continue;
                                }
                                comp[nLocal] = (byte) nextId;
                                queue[tail++] = nLocal;
                            }
                        }
                    }
                }
            }
        }

        int compAt(int cluster, int x, int y) {
            int cs = ClusterGeometry.CLUSTER_SIZE;
            int xMin = ClusterGeometry.minX(cluster);
            int yMin = ClusterGeometry.minY(cluster);
            int lx = x - xMin, ly = y - yMin;
            if (lx < 0 || ly < 0 || lx >= cs || ly >= cs) {
                return 0;
            }
            return intraComp[cluster][ly * cs + lx] & 0xFF;
        }

        void enumerateEntrances() {
            for (int floor = 0; floor < CollisionMap.FLOOR_COUNT; floor++) {
                int floorBase = floor * ClusterGeometry.CLUSTERS_PER_FLOOR;
                for (int cy = 0; cy < ClusterGeometry.CLUSTERS_PER_FLOOR_Y; cy++) {
                    for (int cx = 0; cx < ClusterGeometry.CLUSTERS_X; cx++) {
                        int cluster = floorBase + cy * ClusterGeometry.CLUSTERS_X + cx;
                        if (cx + 1 < ClusterGeometry.CLUSTERS_X) {
                            scanVerticalBoundary(cluster, cluster + 1);
                        }
                        if (cy + 1 < ClusterGeometry.CLUSTERS_PER_FLOOR_Y) {
                            scanHorizontalBoundary(cluster, cluster + ClusterGeometry.CLUSTERS_X);
                        }
                    }
                }
            }
        }

        void scanVerticalBoundary(int clusterA, int clusterB) {
            int xLeft = ClusterGeometry.maxX(clusterA) - 1;
            int xRight = ClusterGeometry.maxX(clusterA);
            int y0 = ClusterGeometry.minY(clusterA);
            int y1 = ClusterGeometry.maxY(clusterA);

            int spanStart = -1;
            for (int y = y0; y <= y1; y++) {
                // Both ways: a pair one of whose tiles is fully blocked can be stepped off, never onto, and is
                // no entrance:
                // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L280
                boolean passable = y < y1
                        && map.inBounds(xLeft, y)
                        && map.inBounds(xRight, y)
                        && map.canStep(xLeft, y, xRight, y, Overlays.NONE)
                        && map.canStep(xRight, y, xLeft, y, Overlays.NONE);
                if (passable) {
                    if (spanStart < 0) {
                        spanStart = y;
                    }
                } else if (spanStart >= 0) {
                    addBoundarySpan(clusterA, xLeft, spanStart, y - 1, clusterB, xRight);
                    spanStart = -1;
                }
            }
        }

        void scanHorizontalBoundary(int clusterA, int clusterB) {
            int yTop = ClusterGeometry.maxY(clusterA) - 1;
            int yBottom = ClusterGeometry.maxY(clusterA);
            int x0 = ClusterGeometry.minX(clusterA);
            int x1 = ClusterGeometry.maxX(clusterA);

            int spanStart = -1;
            for (int x = x0; x <= x1; x++) {
                boolean passable = x < x1
                        && map.inBounds(x, yTop)
                        && map.inBounds(x, yBottom)
                        && map.canStep(x, yTop, x, yBottom, Overlays.NONE)
                        && map.canStep(x, yBottom, x, yTop, Overlays.NONE);
                if (passable) {
                    if (spanStart < 0) {
                        spanStart = x;
                    }
                } else if (spanStart >= 0) {
                    addBoundarySpanHoriz(clusterA, yTop, spanStart, x - 1, clusterB, yBottom);
                    spanStart = -1;
                }
            }
        }

        /**
         * Splits a passable run where the walkable component on either side changes, and puts an entrance
         * pair at the middle of each stretch, so every component that touches the boundary gets one.
         */
        void addBoundarySpan(int clusterA, int xLeft, int yStart, int yEnd, int clusterB, int xRight) {
            int curCompA = compAt(clusterA, xLeft, yStart);
            int curCompB = compAt(clusterB, xRight, yStart);
            int groupStart = yStart;
            for (int y = yStart + 1; y <= yEnd + 1; y++) {
                int ca = (y <= yEnd) ? compAt(clusterA, xLeft, y) : -1;
                int cb = (y <= yEnd) ? compAt(clusterB, xRight, y) : -1;
                if (ca != curCompA || cb != curCompB) {
                    int yMid = (groupStart + (y - 1)) >>> 1;
                    int tileA = absTileId(xLeft, yMid);
                    int tileB = absTileId(xRight, yMid);
                    appendEntrance(clusterA, tileA);
                    appendEntrance(clusterB, tileB);
                    spanByTile.putIfAbsent(tileA, new int[] {absTileId(xLeft, groupStart), absTileId(xLeft, y - 1)});
                    spanByTile.putIfAbsent(tileB, new int[] {absTileId(xRight, groupStart), absTileId(xRight, y - 1)});
                    rawBoundaryPairs.add(new long[] {tileA, tileB});
                    groupStart = y;
                    curCompA = ca;
                    curCompB = cb;
                }
            }
        }

        void addBoundarySpanHoriz(int clusterA, int yTop, int xStart, int xEnd, int clusterB, int yBottom) {
            int curCompA = compAt(clusterA, xStart, yTop);
            int curCompB = compAt(clusterB, xStart, yBottom);
            int groupStart = xStart;
            for (int x = xStart + 1; x <= xEnd + 1; x++) {
                int ca = (x <= xEnd) ? compAt(clusterA, x, yTop) : -1;
                int cb = (x <= xEnd) ? compAt(clusterB, x, yBottom) : -1;
                if (ca != curCompA || cb != curCompB) {
                    int xMid = (groupStart + (x - 1)) >>> 1;
                    int tileA = absTileId(xMid, yTop);
                    int tileB = absTileId(xMid, yBottom);
                    appendEntrance(clusterA, tileA);
                    appendEntrance(clusterB, tileB);
                    spanByTile.putIfAbsent(tileA, new int[] {absTileId(groupStart, yTop), absTileId(x - 1, yTop)});
                    spanByTile.putIfAbsent(
                            tileB, new int[] {absTileId(groupStart, yBottom), absTileId(x - 1, yBottom)});
                    rawBoundaryPairs.add(new long[] {tileA, tileB});
                    groupStart = x;
                    curCompA = ca;
                    curCompB = cb;
                }
            }
        }

        void appendEntrance(int clusterId, int tileId) {
            int[] existing = entrancesByCluster[clusterId];
            if (existing == null) {
                entrancesByCluster[clusterId] = new int[] {tileId};
            } else {
                for (int e : existing) {
                    if (e == tileId) {
                        return;
                    }
                }
                int[] grown = new int[existing.length + 1];
                System.arraycopy(existing, 0, grown, 0, existing.length);
                grown[existing.length] = tileId;
                entrancesByCluster[clusterId] = grown;
            }
        }

        void enumeratePortals() {
            for (Transport t : transports) {
                if (brokenEntryless.contains(t)) {
                    continue;
                }
                List<Point> entries = t.entries();
                if (entries.isEmpty()) {
                    // Used from anywhere: its landing is taken to be the same wherever it is used from.
                    Point dest = t.destinationFrom(Point.of(0, 0));
                    if (dest != null && map.inBounds(dest.x(), dest.y())) {
                        appendPortal(ClusterGeometry.clusterOf(dest.x(), dest.y()), absTileId(dest.x(), dest.y()));
                    }
                    continue;
                }
                for (Point e : entries) {
                    if (!map.inBounds(e.x(), e.y())) {
                        continue;
                    }
                    Point dest = t.destinationFrom(e);
                    if (dest == null || !map.inBounds(dest.x(), dest.y())) {
                        continue;
                    }
                    appendPortal(ClusterGeometry.clusterOf(e.x(), e.y()), absTileId(e.x(), e.y()));
                    appendPortal(ClusterGeometry.clusterOf(dest.x(), dest.y()), absTileId(dest.x(), dest.y()));
                }
            }
            for (BankSite bank : banks) {
                Point tile = bank.tile();
                if (!map.inBounds(tile.x(), tile.y())) {
                    continue;
                }
                appendPortal(ClusterGeometry.clusterOf(tile.x(), tile.y()), absTileId(tile.x(), tile.y()));
            }
        }

        void appendPortal(int clusterId, int tileId) {
            int[] existing = portalsByCluster[clusterId];
            if (existing == null) {
                portalsByCluster[clusterId] = new int[] {tileId};
            } else {
                for (int p : existing) {
                    if (p == tileId) {
                        return;
                    }
                }
                int[] grown = new int[existing.length + 1];
                System.arraycopy(existing, 0, grown, 0, existing.length);
                grown[existing.length] = tileId;
                portalsByCluster[clusterId] = grown;
            }
        }

        /**
         * A one-way step from every fully blocked transport landing to each of its open cardinal
         * neighbours. The server puts a player down on a transport's tile unchecked and lets it step off a
         * blocked tile, but the passes above join walkable tiles only, so a blocked landing on a cluster's
         * edge would reach nothing. One way, so no route walks through the blocked tile.
         *
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/player/Player.java#L2947">Player.teleport</a>
         * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L280">PathValidation.isBlocking</a>
         */
        void enumerateBlockedLandingEscapes() {
            HashSet<Long> seenEdge = new HashSet<>();
            for (Transport t : transports) {
                if (brokenEntryless.contains(t)) {
                    continue;
                }
                List<Point> entries = t.entries();
                if (entries.isEmpty()) {
                    addEscapes(asTile(t.destinationFrom(Point.of(0, 0))), seenEdge);
                    continue;
                }
                for (Point e : entries) {
                    if (!map.inBounds(e.x(), e.y())) {
                        continue;
                    }
                    addEscapes(asTile(t.destinationFrom(e)), seenEdge);
                }
            }
        }

        private static int[] asTile(Point p) {
            return p == null ? null : new int[] {p.x(), p.y()};
        }

        private void addEscapes(int[] tile, HashSet<Long> seenEdge) {
            if (tile == null || !map.inBounds(tile[0], tile[1]) || !map.fullyBlocked(tile[0], tile[1])) {
                return;
            }
            int bx = tile[0], by = tile[1];
            int[][] cards = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
            for (int[] c : cards) {
                int nx = bx + c[0], ny = by + c[1];
                if (!map.inBounds(nx, ny) || map.fullyBlocked(nx, ny)) {
                    continue;
                }
                if (!map.canStep(bx, by, nx, ny, Overlays.NONE)) {
                    continue;
                }
                long from = absTileId(bx, by);
                long to = absTileId(nx, ny);
                long key = (from << 32) | to;
                if (!seenEdge.add(key)) {
                    continue;
                }
                // The neighbour may be no node yet.
                appendPortal(ClusterGeometry.clusterOf(nx, ny), (int) to);
                rawEscapePairs.add(new long[] {from, to});
            }
        }

        void assignNodeIds() {
            int n = ClusterGeometry.TOTAL_CLUSTERS;
            clusterFirstNode = new int[n + 1];
            int cursor = 0;

            for (int c = 0; c < n; c++) {
                clusterFirstNode[c] = cursor;
                cursor += clusterNodeCountAtBuild(c);
            }
            clusterFirstNode[n] = cursor;
            totalNodes = cursor;
            nodeX = new int[totalNodes];
            nodeY = new int[totalNodes];
            nodeCluster = new int[totalNodes];
            nodeSpanLo = new int[totalNodes];
            nodeSpanHi = new int[totalNodes];

            for (int c = 0; c < n; c++) {
                int start = clusterFirstNode[c];
                int idx = start;
                int[] ents = entrancesByCluster[c];
                if (ents != null) {
                    for (int e : ents) {
                        nodeX[idx] = unX(e);
                        nodeY[idx] = unY(e);
                        nodeCluster[idx] = c;
                        int[] span = spanByTile.get(e);
                        nodeSpanLo[idx] = span != null ? span[0] : e;
                        nodeSpanHi[idx] = span != null ? span[1] : e;
                        tileToNode.put(e, idx);
                        idx++;
                    }
                }
                int[] ports = portalsByCluster[c];
                if (ports != null) {
                    outer:
                    for (int p : ports) {
                        if (ents != null) {
                            for (int e : ents) {
                                if (e == p) {
                                    // A portal on an entrance's tile is that entrance.
                                    continue outer;
                                }
                            }
                        }
                        nodeX[idx] = unX(p);
                        nodeY[idx] = unY(p);
                        nodeCluster[idx] = c;
                        nodeSpanLo[idx] = p;
                        nodeSpanHi[idx] = p;
                        tileToNode.put(p, idx);
                        idx++;
                    }
                }
                clusterFirstNode[c + 1] = idx;
            }
            totalNodes = clusterFirstNode[n];
            if (totalNodes < nodeX.length) {
                nodeX = Arrays.copyOf(nodeX, totalNodes);
                nodeY = Arrays.copyOf(nodeY, totalNodes);
                nodeCluster = Arrays.copyOf(nodeCluster, totalNodes);
                nodeSpanLo = Arrays.copyOf(nodeSpanLo, totalNodes);
                nodeSpanHi = Arrays.copyOf(nodeSpanHi, totalNodes);
            }
            int[] tmpBankIds = new int[banks.size()];
            int bankCount = 0;
            for (BankSite bank : banks) {
                Point tile = bank.tile();
                Integer node = tileToNode.get(absTileId(tile.x(), tile.y()));
                if (node == null) {
                    continue;
                }
                tmpBankIds[bankCount++] = node;
            }
            bankNodes = Arrays.copyOf(tmpBankIds, bankCount);
        }

        int clusterNodeCountAtBuild(int c) {
            int n = 0;
            if (entrancesByCluster[c] != null) {
                n += entrancesByCluster[c].length;
            }
            if (portalsByCluster[c] != null) {
                n += portalsByCluster[c].length;
            }
            return n;
        }

        void computeIntraClusterMatrices() {
            clusterMatrix = new int[ClusterGeometry.TOTAL_CLUSTERS][];
            for (int c = 0; c < ClusterGeometry.TOTAL_CLUSTERS; c++) {
                int count = clusterFirstNode[c + 1] - clusterFirstNode[c];
                if (count == 0) {
                    continue;
                }
                if (count == 1) {
                    clusterMatrix[c] = new int[] {0};
                    continue;
                }
                clusterMatrix[c] = computeClusterMatrix(c, count);
            }
        }

        int[] computeClusterMatrix(int c, int n) {
            int xMin = ClusterGeometry.minX(c);

            int yMin = ClusterGeometry.minY(c);

            int w = ClusterGeometry.CLUSTER_SIZE;
            int cellCount = w * w;
            int[] result = new int[n * n];
            Arrays.fill(result, Integer.MAX_VALUE);
            int firstNode = clusterFirstNode[c];

            // Every step costs the same, so a breadth-first search gives the distances.
            int[] out = edges(map, edgeTables, c).out;
            int[] dist = new int[cellCount];
            int[] queue = new int[cellCount];
            for (int src = 0; src < n; src++) {
                Arrays.fill(dist, Integer.MAX_VALUE);
                int sx = nodeX[firstNode + src];
                int sy = nodeY[firstNode + src];
                int sLocal = localId(sx, sy, xMin, yMin, w);
                if (sLocal < 0) {
                    result[src * n + src] = 0;
                    continue;
                }
                dist[sLocal] = 0;
                int head = 0;
                int tail = 0;
                queue[tail++] = sLocal;
                while (head < tail) {
                    int local = queue[head++];
                    int nCost = dist[local] + STEP_COST;
                    for (int m = out[local]; m != 0; m &= m - 1) {
                        int nLocal = local + ClusterEdges.STEP[Integer.numberOfTrailingZeros(m)];
                        if (dist[nLocal] == Integer.MAX_VALUE) {
                            dist[nLocal] = nCost;
                            queue[tail++] = nLocal;
                        }
                    }
                }
                for (int dst = 0; dst < n; dst++) {
                    if (dst == src) {
                        result[src * n + src] = 0;
                        continue;
                    }
                    int dx = nodeX[firstNode + dst];
                    int dy = nodeY[firstNode + dst];
                    int dLocal = localId(dx, dy, xMin, yMin, w);
                    if (dLocal < 0) {
                        continue;
                    }
                    int d = dist[dLocal];
                    if (d != Integer.MAX_VALUE) {
                        result[src * n + dst] = d;
                    }
                }
            }
            return result;
        }

        private static int localId(int x, int y, int xMin, int yMin, int w) {
            int lx = x - xMin;
            int ly = y - yMin;
            if (lx < 0 || ly < 0 || lx >= w || ly >= w) {
                return -1;
            }
            return ly * w + lx;
        }

        private static long packDijkstraKey(int cost, int localId) {
            return ((long) cost << 32) | (localId & 0xFFFFFFFFL);
        }

        private static void siftUp(long[] heap, int i) {
            long key = heap[i];
            while (i > 0) {
                int p = (i - 1) >>> 1;
                if (heap[p] <= key) {
                    break;
                }
                heap[i] = heap[p];
                i = p;
            }
            heap[i] = key;
        }

        private static void siftDown(long[] heap, int n, int i) {
            long key = heap[i];
            int half = n >>> 1;
            while (i < half) {
                int l = (i << 1) + 1;
                int r = l + 1;
                int smallest = l;
                if (r < n && heap[r] < heap[l]) {
                    smallest = r;
                }
                if (heap[smallest] >= key) {
                    break;
                }
                heap[i] = heap[smallest];
                i = smallest;
            }
            heap[i] = key;
        }

        void buildBoundaryEdges() {
            int total = rawBoundaryPairs.size();
            boundaryA = new int[total];
            boundaryB = new int[total];
            int kept = 0;
            for (long[] pair : rawBoundaryPairs) {
                int a = (int) pair[0];
                int b = (int) pair[1];
                Integer na = tileToNode.get(a);
                Integer nb = tileToNode.get(b);
                if (na == null || nb == null) {
                    continue;
                }
                boundaryA[kept] = na;
                boundaryB[kept] = nb;
                kept++;
            }
            boundaryEdgeCount = kept;
            if (kept < total) {
                boundaryA = Arrays.copyOf(boundaryA, kept);
                boundaryB = Arrays.copyOf(boundaryB, kept);
            }

            int et = rawEscapePairs.size();
            escapeFromNode = new int[et];
            escapeToNode = new int[et];
            int ek = 0;
            for (long[] pair : rawEscapePairs) {
                Integer nFrom = tileToNode.get((int) pair[0]);
                Integer nTo = tileToNode.get((int) pair[1]);
                if (nFrom == null || nTo == null) {
                    continue;
                }
                escapeFromNode[ek] = nFrom;
                escapeToNode[ek] = nTo;
                ek++;
            }
            escapeEdgeCount = ek;
            if (ek < et) {
                escapeFromNode = Arrays.copyOf(escapeFromNode, ek);
                escapeToNode = Arrays.copyOf(escapeToNode, ek);
            }
        }

        void buildTransportEdges() {
            ArrayList<int[]> concrete = new ArrayList<>();
            ArrayList<int[]> anywhere = new ArrayList<>();
            int nT = transports.size();
            for (int i = 0; i < nT; i++) {
                Transport t = transports.get(i);
                if (brokenEntryless.contains(t)) {
                    continue;
                }
                List<Point> entries = t.entries();
                if (entries.isEmpty()) {
                    Point dest = t.destinationFrom(Point.of(0, 0));
                    if (dest == null || !map.inBounds(dest.x(), dest.y())) {
                        continue;
                    }
                    Integer destNode = tileToNode.get(absTileId(dest.x(), dest.y()));
                    if (destNode == null) {
                        continue;
                    }
                    anywhere.add(new int[] {destNode, i});
                    continue;
                }
                for (Point e : entries) {
                    if (!map.inBounds(e.x(), e.y())) {
                        continue;
                    }
                    Point dest = t.destinationFrom(e);
                    if (dest == null || !map.inBounds(dest.x(), dest.y())) {
                        continue;
                    }
                    Integer eNode = tileToNode.get(absTileId(e.x(), e.y()));
                    Integer dNode = tileToNode.get(absTileId(dest.x(), dest.y()));
                    if (eNode == null || dNode == null) {
                        continue;
                    }
                    concrete.add(new int[] {eNode, dNode, i});
                }
            }
            transportFrom = new int[concrete.size()];
            transportTo = new int[concrete.size()];
            transportIdx = new int[concrete.size()];
            for (int k = 0; k < concrete.size(); k++) {
                int[] e = concrete.get(k);
                transportFrom[k] = e[0];
                transportTo[k] = e[1];
                transportIdx[k] = e[2];
            }
            transportEdgeCount = concrete.size();

            anywhereDestNode = new int[anywhere.size()];
            anywhereTransportIdx = new int[anywhere.size()];
            for (int k = 0; k < anywhere.size(); k++) {
                int[] e = anywhere.get(k);
                anywhereDestNode[k] = e[0];
                anywhereTransportIdx[k] = e[1];
            }
        }

        /**
         * One cast source per teleport region, and each source's cast-entry nodes. A teleport lands where it
         * lands wherever it is cast, so a route starting in the region casts from its start tile and a cast
         * further on pays only where the start could not: a node of the region a route can reach from
         * outside it (any node of a cluster holding a tile outside it, or the head of an arc from outside),
         * a bank, where the pack changes, or a teleport landing.
         */
        void buildCastSources() {
            List<TeleportRegion> regions = new ArrayList<>();
            anywhereSource = new int[anywhereTransportIdx.length];
            for (int k = 0; k < anywhereTransportIdx.length; k++) {
                TeleportRegion r = transports.get(anywhereTransportIdx[k]).region();
                int s = regions.indexOf(r);
                if (s < 0) {
                    s = regions.size();
                    regions.add(r);
                }
                anywhereSource[k] = s;
            }
            sourceRegion = regions.toArray(new TeleportRegion[0]);
            castEntryFirst = new int[sourceRegion.length + 1];
            IntArrayList entries = new IntArrayList();
            for (int s = 0; s < sourceRegion.length; s++) {
                TeleportRegion r = sourceRegion[s];
                boolean[] in = new boolean[totalNodes];
                for (int n = 0; n < totalNodes; n++) {
                    in[n] = r.contains(nodeX[n], nodeY[n]);
                }
                boolean[] reached = new boolean[totalNodes];
                for (int c = 0; c < ClusterGeometry.TOTAL_CLUSTERS; c++) {
                    int first = clusterFirstNode[c];
                    int last = clusterFirstNode[c + 1];
                    if (first < last && leavesRegion(c, r)) {
                        Arrays.fill(reached, first, last, true);
                    }
                }
                for (int i = 0; i < boundaryEdgeCount; i++) {
                    reached[boundaryB[i]] |= !in[boundaryA[i]];
                    reached[boundaryA[i]] |= !in[boundaryB[i]];
                }
                for (int i = 0; i < escapeEdgeCount; i++) {
                    reached[escapeToNode[i]] |= !in[escapeFromNode[i]];
                }
                for (int i = 0; i < transportEdgeCount; i++) {
                    reached[transportTo[i]] |= !in[transportFrom[i]];
                }
                for (int bank : bankNodes) {
                    reached[bank] = true;
                }
                for (int landing : anywhereDestNode) {
                    reached[landing] = true;
                }
                for (int n = 0; n < totalNodes; n++) {
                    if (in[n] && reached[n]) {
                        entries.add(n);
                    }
                }
                castEntryFirst[s + 1] = entries.size();
            }
            castEntryNodes = entries.toIntArray();
        }

        private boolean leavesRegion(int c, TeleportRegion r) {
            int floorEnd = (ClusterGeometry.floorOf(c) + 1) * CollisionMap.FLOOR_HEIGHT;
            int x1 = Math.min(ClusterGeometry.maxX(c), map.width());
            int y1 = Math.min(ClusterGeometry.maxY(c), floorEnd);
            for (int y = ClusterGeometry.minY(c); y < y1; y++) {
                for (int x = ClusterGeometry.minX(c); x < x1; x++) {
                    if (!r.contains(x, y)) {
                        return true;
                    }
                }
            }
            return false;
        }

        ClusterGraph toGraph() {
            buildPerNodeAdjacency();
            return new ClusterGraph(
                    map,
                    transports,
                    clusterFirstNode,
                    totalNodes,
                    nodeX,
                    nodeY,
                    nodeCluster,
                    nodeSpanLo,
                    nodeSpanHi,
                    clusterMatrix,
                    boundaryA,
                    boundaryB,
                    boundaryEdgeCount,
                    transportFrom,
                    transportTo,
                    transportIdx,
                    transportEdgeCount,
                    anywhereDestNode,
                    anywhereTransportIdx,
                    anywhereSource,
                    sourceRegion,
                    castEntryFirst,
                    castEntryNodes,
                    bankNodes,
                    outTransports,
                    outBoundary,
                    edgeTables);
        }

        // Escape steps go one way, landing to neighbour.
        void buildPerNodeAdjacency() {
            int[] tCount = new int[totalNodes];
            for (int i = 0; i < transportEdgeCount; i++) {
                tCount[transportFrom[i]]++;
            }
            outTransports = new int[totalNodes][];
            for (int n = 0; n < totalNodes; n++) {
                outTransports[n] = tCount[n] == 0 ? EMPTY_INT : new int[tCount[n] * 2];
            }
            int[] tFill = new int[totalNodes];
            for (int i = 0; i < transportEdgeCount; i++) {
                int from = transportFrom[i];
                int to = transportTo[i];
                int idx = transportIdx[i];
                int o = tFill[from];
                outTransports[from][o] = idx;
                outTransports[from][o + 1] = to;
                tFill[from] = o + 2;
            }
            int[] bCount = new int[totalNodes];
            for (int i = 0; i < boundaryEdgeCount; i++) {
                bCount[boundaryA[i]]++;
                bCount[boundaryB[i]]++;
            }
            for (int i = 0; i < escapeEdgeCount; i++) {
                bCount[escapeFromNode[i]]++;
            }
            outBoundary = new int[totalNodes][];
            for (int n = 0; n < totalNodes; n++) {
                outBoundary[n] = bCount[n] == 0 ? EMPTY_INT : new int[bCount[n]];
            }
            int[] bFill = new int[totalNodes];
            for (int i = 0; i < boundaryEdgeCount; i++) {
                int a = boundaryA[i];
                int b = boundaryB[i];
                outBoundary[a][bFill[a]++] = b;
                outBoundary[b][bFill[b]++] = a;
            }
            for (int i = 0; i < escapeEdgeCount; i++) {
                int from = escapeFromNode[i];
                outBoundary[from][bFill[from]++] = escapeToNode[i];
            }
        }

        static int absTileId(int x, int y) {
            return y * CollisionMap.WIDTH + x;
        }

        static int unX(int tileId) {
            return tileId - (tileId / CollisionMap.WIDTH) * CollisionMap.WIDTH;
        }

        static int unY(int tileId) {
            return tileId / CollisionMap.WIDTH;
        }
    }
}
