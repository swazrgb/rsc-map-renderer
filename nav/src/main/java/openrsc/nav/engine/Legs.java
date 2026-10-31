package openrsc.nav.engine;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.cache.NavCache;
import openrsc.nav.cache.NavCacheStats;
import openrsc.nav.cache.NavCaches;
import openrsc.nav.cache.NavCodec;
import openrsc.nav.cache.NavStore;

/**
 * The tile-level ends of every route through the hierarchy: per cluster, the walk from any of its
 * tiles to each of its nodes ({@code toNodes}) and from each node to any tile ({@code fromNodes}).
 *
 * <p>Costs are weights in deci-ticks: {@link #STEP_COST} a step, and under a danger field the
 * field's charge as well, on the tile each step enters for {@code toNodes} and on the tile it leaves
 * for {@code fromNodes}. A node costs the least over the tiles of its span
 * ({@link ClusterGraph#nodeSpanLo}). {@link Integer#MAX_VALUE} means no walk inside the cluster.
 * Static map only, so an answer is a function of (cluster, direction, danger view) and is kept
 * under exactly that. Safe for concurrent use.
 */
final class Legs {

    /**
     * One step's weight and time in deci-ticks, diagonal or straight: the server moves a walking player
     * one tile a tick.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/WalkingQueue.java#L79">WalkingQueue.processNextMovement</a>
     */
    static final int STEP_COST = 10;

    /** Bumped when a change here can change what a persisted leg plane holds: sweeps, layout, codec. */
    static final int PLANE_VERSION = 2;

    /** One cluster's walks in one direction under one danger view, for every tile and every node. */
    static final class ClusterLegs {

        final int xMin;
        final int yMin;
        final int nodeCount;
        /**
         * Cell-major: for each tile of the cluster's grid in row order, every node's entry in turn,
         * {@link #stride} ints each, the weight and then, on a plane that keeps one, the time. Readers ask
         * for one tile across all nodes, which this layout keeps in one short run.
         */
        final LegCells cells;
        /** 2 when an entry keeps a time beside its weight, 1 when the weight is the time. */
        final int stride;

        ClusterLegs(int xMin, int yMin, int nodeCount, LegCells cells, int stride) {
            this.xMin = xMin;
            this.yMin = yMin;
            this.nodeCount = nodeCount;
            this.cells = cells;
            this.stride = stride;
        }

        /**
         * A plane from the builders' node-major layout ({@code grids[node * 4096 + tile]}, {@code times}
         * alike, or null where weight is time), transposed to {@link #cells}' layout.
         */
        static ClusterLegs fromNodeMajor(int xMin, int yMin, int nodeCount, int[] grids, int[] times) {
            int cellCount = ClusterGeometry.CLUSTER_SIZE * ClusterGeometry.CLUSTER_SIZE;
            int stride = times == null ? 1 : 2;
            int[] cells = new int[nodeCount * cellCount * stride];
            int at = 0;
            if (times == null) {
                for (int l = 0; l < cellCount; l++) {
                    for (int src = l; src < l + nodeCount * cellCount; src += cellCount) {
                        cells[at++] = grids[src];
                    }
                }
            } else {
                for (int l = 0; l < cellCount; l++) {
                    for (int src = l; src < l + nodeCount * cellCount; src += cellCount) {
                        cells[at++] = grids[src];
                        cells[at++] = times[src];
                    }
                }
            }
            return new ClusterLegs(xMin, yMin, nodeCount, new LegCells.OnHeap(cells), stride);
        }

        /** What the plane store weighs this plane at. */
        long bytes() {
            return 16L + cells.byteSize();
        }

        /** Where tile {@code (x, y)}'s entries start in {@link #cells}, or -1 off the cluster. */
        int cellAt(int x, int y) {
            int cs = ClusterGeometry.CLUSTER_SIZE;
            int lx = x - xMin;
            int ly = y - yMin;
            if (lx < 0 || ly < 0 || lx >= cs || ly >= cs) {
                return -1;
            }
            return (ly * cs + lx) * nodeCount * stride;
        }

        int[] costsAt(int x, int y) {
            int[] out = new int[nodeCount];
            int at = cellAt(x, y);
            if (at < 0) {
                Arrays.fill(out, Integer.MAX_VALUE);
                return out;
            }
            for (int i = 0; i < nodeCount; i++) {
                out[i] = cells.get(at + i * stride);
            }
            return out;
        }
    }

    /**
     * {@code -Dnav.legs.verify=true}: answer every ask by the targeted sweep and from the full plane,
     * and throw where they disagree. The two seed opposite ends and charge danger by mirrored
     * conventions, and a mistake there gives wrong routes, not errors.
     */
    private static final boolean VERIFY_SWEEP = Boolean.getBoolean("nav.legs.verify");

    private final ClusterGraph graph;
    private final int w;

    private final ConcurrentLinkedQueue<long[]> heapPool = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<int[]> gridPool = new ConcurrentLinkedQueue<>();

    private final NavCache<ClusterKey, ClusterLegs> clusterCache;

    private final java.util.Set<ClusterKey> missedOnce =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private final boolean retains;

    private final AtomicLong planeBuilds = new AtomicLong();

    private final AtomicLong sweeps = new AtomicLong();

    /** {@code stamp} is what a persisted plane is computed from; only a store that keeps files asks for it. */
    Legs(CollisionMap map, ClusterGraph graph, NavCaches caches, Supplier<byte[]> stamp) {

        this.graph = graph;
        this.w = CollisionMap.WIDTH;
        this.clusterCache = caches.<ClusterKey, ClusterLegs>persisted(
                NavStore.CLUSTER_LEGS, ClusterLegs::bytes, new PlaneCodec(stamp));
        this.retains = caches.retains();
    }

    ClusterLegs toNodes(int cluster, DangerField danger) {
        return clusterLegs(cluster, true, danger);
    }

    int[] costsAt(int cluster, int x, int y, boolean toNodes, DangerField danger, int[] outTimes) {
        return costsAtAny(cluster, new int[] {x}, new int[] {y}, 1, toNodes, danger, outTimes);
    }

    /**
     * {@link #costsAtAny} that also fills {@code outTimes}, when not null, with each node's time along
     * the walk its weight chose, index-aligned with the result.
     */
    int[] costsAtAny(int cluster, int[] xs, int[] ys, int count, boolean toNodes, DangerField danger, int[] outTimes) {
        boolean dangerHere = danger != null && danger.anyDanger() && danger.clusterPenalty(cluster) != 0;
        DangerField effective = dangerHere ? danger : null;
        ClusterKey k = new ClusterKey(cluster, toNodes, dangerHere ? danger.viewSignature(cluster) : 0);

        ClusterLegs done = clusterCache.getIfPresent(k);
        if (done != null && !VERIFY_SWEEP) {
            return minOver(done, xs, ys, count, outTimes);
        }

        if (!VERIFY_SWEEP && retains && !missedOnce.add(k)) {
            return minOver(clusterLegs(cluster, toNodes, danger), xs, ys, count, outTimes);
        }
        sweeps.incrementAndGet();
        int[] swept = sweepFromTiles(cluster, xs, ys, count, toNodes, effective, outTimes);
        if (VERIFY_SWEEP) {
            int[] planedTimes = outTimes == null ? null : new int[outTimes.length];
            int[] planed = minOver(clusterLegs(cluster, toNodes, danger), xs, ys, count, planedTimes);
            if (!Arrays.equals(swept, planed)) {
                throw new IllegalStateException(String.format(
                        "targeted sweep disagrees with the built plane at cluster %d tile (%d,%d) toNodes=%s%n"
                                + "  swept  = %s%n  planed = %s",
                        cluster, xs[0], ys[0], toNodes, Arrays.toString(swept), Arrays.toString(planed)));
            }
            if (planedTimes != null && !Arrays.equals(outTimes, planedTimes)) {
                throw new IllegalStateException(String.format(
                        "targeted sweep's TIME disagrees with the built plane at cluster %d tile (%d,%d) "
                                + "toNodes=%s%n  swept  = %s%n  planed = %s",
                        cluster, xs[0], ys[0], toNodes, Arrays.toString(outTimes), Arrays.toString(planedTimes)));
            }
            return swept;
        }
        return swept;
    }

    long planeBuilds() {
        return planeBuilds.get();
    }

    long sweeps() {
        return sweeps.get();
    }

    String stats() {
        NavCacheStats cs = clusterCache.stats();
        return String.format(
                "legs: %d entries, %dMB held, hitRate=%.3f, loads=%d, missCost=%.2fms, " + "evictionWeight=%dMB",
                cs.entries(), cs.heldBytes() >> 20, cs.hitRate(), cs.loads(), cs.missCostMs(), cs.evictedBytes() >> 20);
    }

    private ClusterEdges edges(int cluster) {
        return graph.edges(cluster);
    }

    private ClusterLegs clusterLegs(int cluster, boolean toNodes, DangerField danger) {
        boolean dangerHere = danger != null && danger.anyDanger() && danger.clusterPenalty(cluster) != 0;
        ClusterKey k = new ClusterKey(cluster, toNodes, dangerHere ? danger.viewSignature(cluster) : 0);
        return clusterCache.get(k, key -> {
            planeBuilds.incrementAndGet();
            return buildClusterLegs(cluster, toNodes, dangerHere ? danger : null);
        });
    }

    private static int[] minOver(ClusterLegs plane, int[] xs, int[] ys, int count, int[] outTimes) {
        int[] out = plane.costsAt(xs[0], ys[0]);
        LegCells cells = plane.cells;
        int stride = plane.stride;
        int timeOffset = stride - 1;
        int timed = outTimes == null ? 0 : Math.min(out.length, outTimes.length);
        int first = plane.cellAt(xs[0], ys[0]);
        for (int i = 0; i < timed; i++) {
            outTimes[i] = first < 0 ? Integer.MAX_VALUE : cells.get(first + i * stride + timeOffset);
        }
        for (int t = 1; t < count; t++) {
            int at = plane.cellAt(xs[t], ys[t]);
            if (at < 0) {
                continue;
            }
            for (int i = 0; i < out.length; i++) {
                int c = cells.get(at + i * stride);
                // Not Math.min: the time has to be the winning tile's.
                if (c < out[i]) {
                    out[i] = c;
                    if (i < timed) {
                        outTimes[i] = cells.get(at + i * stride + timeOffset);
                    }
                }
            }
        }
        return out;
    }

    private int[] sweepFromTiles(
            int cluster, int[] xs, int[] ys, int count, boolean toNodes, DangerField danger, int[] outTimes) {
        int xMin = ClusterGeometry.minX(cluster);
        int xMax = ClusterGeometry.maxX(cluster);
        int yMin = ClusterGeometry.minY(cluster);
        int yMax = ClusterGeometry.maxY(cluster);
        if (danger != null && !danger.intersects(xMin, yMin, xMax - 1, yMax - 1)) {
            danger = null;
        }
        short[] dBase = danger == null ? null : danger.baseCells(cluster);
        short[] dCeil = danger == null ? null : danger.ceilCells(cluster);
        int dGate = danger == null ? 0 : danger.gate();
        int dScale = danger == null ? 0 : danger.scale();
        int cs = ClusterGeometry.CLUSTER_SIZE;
        int cellCount = cs * cs;
        int nodeCount = graph.clusterNodeCount(cluster);
        int firstNode = graph.clusterFirstNode(cluster);
        int[] out = new int[nodeCount];
        Arrays.fill(out, Integer.MAX_VALUE);
        if (nodeCount == 0) {
            return out;
        }

        int[] dist = borrowGrid(cellCount);
        Arrays.fill(dist, 0, cellCount, Integer.MAX_VALUE);
        int[] time = danger == null ? null : new int[cellCount];
        // Seeded at the tile, not the node, so the masks are fillNode's swapped, and in both directions the
        // danger charge falls on the cell the sweep newly reaches.
        int[] adj = toNodes ? edges(cluster).out : edges(cluster).in;

        if (danger == null) {
            // Every step costs the same, so first reach is shortest: a FIFO queue, each cell enqueued once.
            int[] queue = borrowGrid(cellCount);
            int head = 0;
            int tail = 0;
            for (int t = 0; t < count; t++) {
                int x = xs[t];
                int y = ys[t];
                if (x < xMin || x >= xMax || y < yMin || y >= yMax) {
                    continue;
                }
                int start = (y - yMin) * cs + (x - xMin);
                if (dist[start] == Integer.MAX_VALUE) {
                    dist[start] = 0;
                    queue[tail++] = start;
                }
            }
            if (tail == 0) {
                gridPool.offer(queue);
                gridPool.offer(dist);
                return out;
            }
            while (head < tail) {
                int local = queue[head++];
                int nCost = dist[local] + 10;
                int feasible = adj[local] & ClusterEdges.FEASIBLE_MASK;
                while (feasible != 0) {
                    int d = Integer.numberOfTrailingZeros(feasible);
                    feasible &= feasible - 1;
                    int nLocal = local + ClusterEdges.STEP[d];
                    if (dist[nLocal] == Integer.MAX_VALUE) {
                        dist[nLocal] = nCost;
                        queue[tail++] = nLocal;
                    }
                }
            }
            gridPool.offer(queue);
        } else {
            long[] heap = borrow(cellCount * 8);
            int heapSize = 0;
            boolean seeded = false;
            for (int t = 0; t < count; t++) {
                int x = xs[t];
                int y = ys[t];
                if (x < xMin || x >= xMax || y < yMin || y >= yMax) {
                    continue;
                }
                int start = (y - yMin) * cs + (x - xMin);
                dist[start] = 0;
                heap = ensure(heap, heapSize + 1);
                heap[heapSize++] = packKey(0, start);
                siftUp(heap, heapSize - 1);
                seeded = true;
            }
            if (!seeded) {
                heapPool.offer(heap);
                gridPool.offer(dist);
                return out;
            }
            while (heapSize > 0) {
                long top = heap[0];
                int g = (int) (top >>> 20);
                int local = (int) (top & 0xFFFFF);
                heapSize--;
                if (heapSize > 0) {
                    heap[0] = heap[heapSize];
                    siftDown(heap, heapSize, 0);
                }
                if (g > dist[local]) {
                    continue;
                }
                int feasible = adj[local] & ClusterEdges.FEASIBLE_MASK;
                while (feasible != 0) {
                    int d = Integer.numberOfTrailingZeros(feasible);
                    feasible &= feasible - 1;
                    int nLocal = local + ClusterEdges.STEP[d];
                    int nCost = g + STEP_COST;
                    if (dCeil != null && dCeil[nLocal] > dGate) {
                        nCost += dBase[nLocal] * dScale;
                    }
                    if (nCost < dist[nLocal]) {
                        dist[nLocal] = nCost;
                        if (time != null) {
                            time[nLocal] = time[local] + STEP_COST;
                        }
                        heap = ensure(heap, heapSize + 1);
                        heap[heapSize++] = packKey(nCost, nLocal);
                        siftUp(heap, heapSize - 1);
                    }
                }
            }
            heapPool.offer(heap);
        }

        for (int ord = 0; ord < nodeCount; ord++) {
            int node = firstNode + ord;
            int lo = graph.nodeSpanLo(node);
            int hi = graph.nodeSpanHi(node);
            int x0 = Math.min(lo % w, hi % w);
            int x1 = Math.max(lo % w, hi % w);
            int y0 = Math.min(lo / w, hi / w);
            int y1 = Math.max(lo / w, hi / w);
            int best = Integer.MAX_VALUE;
            int bestTime = Integer.MAX_VALUE;
            for (int sy = y0; sy <= y1; sy++) {
                for (int sx = x0; sx <= x1; sx++) {
                    int l = local(sx, sy, xMin, yMin, cs);
                    if (l >= 0 && dist[l] < best) {
                        best = dist[l];
                        // The span's least is a choice of tile, and the time is that tile's.
                        bestTime = time == null ? dist[l] : time[l];
                    }
                }
            }
            out[ord] = best;
            if (outTimes != null && ord < outTimes.length) {
                outTimes[ord] = bestTime;
            }
        }
        gridPool.offer(dist);
        return out;
    }

    private ClusterLegs buildClusterLegs(int cluster, boolean toNodes, DangerField danger) {
        int cs = ClusterGeometry.CLUSTER_SIZE;
        int nodeCount = graph.clusterNodeCount(cluster);
        int[] grids = new int[nodeCount * cs * cs];
        int[] times = danger == null ? null : new int[nodeCount * cs * cs];
        for (int ord = 0; ord < nodeCount; ord++) {
            fillNode(grids, times, ord, cluster, toNodes, danger);
        }
        return ClusterLegs.fromNodeMajor(
                ClusterGeometry.minX(cluster), ClusterGeometry.minY(cluster), nodeCount, grids, times);
    }

    /** One node's plane, written node-major at {@code grids[ord * 4096]}: a sweep seeded at its span. */
    private void fillNode(int[] grids, int[] times, int ord, int cluster, boolean toNodes, DangerField danger) {
        int xMin = ClusterGeometry.minX(cluster);
        int xMax = ClusterGeometry.maxX(cluster);
        int yMin = ClusterGeometry.minY(cluster);
        int yMax = ClusterGeometry.maxY(cluster);
        if (danger != null && !danger.intersects(xMin, yMin, xMax - 1, yMax - 1)) {
            danger = null;
        }
        short[] dBase = danger == null ? null : danger.baseCells(cluster);
        short[] dCeil = danger == null ? null : danger.ceilCells(cluster);
        int dGate = danger == null ? 0 : danger.gate();
        int dScale = danger == null ? 0 : danger.scale();
        int cs = ClusterGeometry.CLUSTER_SIZE;
        int cellCount = cs * cs;
        int firstNode = graph.clusterFirstNode(cluster);
        int base = ord * cellCount;
        Arrays.fill(grids, base, base + cellCount, Integer.MAX_VALUE);
        int node = firstNode + ord;
        int lo = graph.nodeSpanLo(node);
        int hi = graph.nodeSpanHi(node);
        int x0 = Math.min(lo % w, hi % w);
        int x1 = Math.max(lo % w, hi % w);
        int y0 = Math.min(lo / w, hi / w);
        int y1 = Math.max(lo / w, hi / w);
        // Seeded at the node: a toNodes plane walks its steps backwards.
        int[] adj = toNodes ? edges(cluster).in : edges(cluster).out;

        if (danger == null) {
            int[] queue = borrowGrid(cellCount);
            int head = 0;
            int tail = 0;
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    int l = local(x, y, xMin, yMin, cs);
                    if (l >= 0 && grids[base + l] == Integer.MAX_VALUE) {
                        grids[base + l] = 0;
                        queue[tail++] = l;
                    }
                }
            }
            while (head < tail) {
                int localIdx = queue[head++];
                int nCost = grids[base + localIdx] + 10;
                int feasible = adj[localIdx] & ClusterEdges.FEASIBLE_MASK;
                while (feasible != 0) {
                    int d = Integer.numberOfTrailingZeros(feasible);
                    feasible &= feasible - 1;
                    int nLocal = localIdx + ClusterEdges.STEP[d];
                    if (grids[base + nLocal] == Integer.MAX_VALUE) {
                        grids[base + nLocal] = nCost;
                        queue[tail++] = nLocal;
                    }
                }
            }
            gridPool.offer(queue);
            return;
        }

        long[] heap = borrow(cellCount * 8);
        int heapSize = 0;
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int l = local(x, y, xMin, yMin, cs);
                if (l >= 0) {
                    grids[base + l] = 0;
                    if (times != null) {
                        times[base + l] = 0;
                    }
                    heap = ensure(heap, heapSize + 1);
                    heap[heapSize++] = packKey(0, l);
                }
            }
        }
        while (heapSize > 0) {
            long top = heap[0];
            int g = (int) (top >>> 20);
            int localIdx = (int) (top & 0xFFFFF);
            heapSize--;
            if (heapSize > 0) {
                heap[0] = heap[heapSize];
                siftDown(heap, heapSize, 0);
            }
            if (g > grids[base + localIdx]) {
                continue;
            }
            int pen = dCeil != null && dCeil[localIdx] > dGate ? dBase[localIdx] * dScale : 0;
            int feasible = adj[localIdx] & ClusterEdges.FEASIBLE_MASK;
            while (feasible != 0) {
                int d = Integer.numberOfTrailingZeros(feasible);
                feasible &= feasible - 1;
                int nLocal = localIdx + ClusterEdges.STEP[d];
                int nCost = g + STEP_COST + pen;
                if (nCost < grids[base + nLocal]) {
                    grids[base + nLocal] = nCost;
                    if (times != null) {
                        times[base + nLocal] = times[base + localIdx] + STEP_COST;
                    }
                    heap = ensure(heap, heapSize + 1);
                    heap[heapSize++] = packKey(nCost, nLocal);
                    siftUp(heap, heapSize - 1);
                }
            }
        }
        heapPool.offer(heap);
    }

    private long[] borrow(int minSize) {
        long[] cached = heapPool.poll();
        if (cached != null && cached.length >= minSize) {
            return cached;
        }
        return new long[Math.max(minSize, 32768)];
    }

    private int[] borrowGrid(int minSize) {
        int[] cached = gridPool.poll();
        return cached != null && cached.length >= minSize ? cached : new int[Math.max(minSize, 4096)];
    }

    private static int local(int x, int y, int xMin, int yMin, int cs) {
        int lx = x - xMin;
        int ly = y - yMin;
        if (lx < 0 || ly < 0 || lx >= cs || ly >= cs) {
            return -1;
        }
        return ly * cs + lx;
    }

    private static long packKey(int cost, int id) {
        return ((long) cost << 20) | (id & 0xFFFFFL);
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

    private static long[] ensure(long[] heap, int needed) {
        if (heap.length >= needed) {
            return heap;
        }
        return Arrays.copyOf(heap, Math.max(heap.length * 2, needed));
    }

    private record ClusterKey(int cluster, boolean toNodes, int dangerSig) {}

    /**
     * A plane as a store's file keeps it. The key is 9 bytes: cluster, direction, danger view. The value
     * is a 16-byte header (xMin, yMin, nodeCount, stride) and then the cells, read back in place.
     */
    private record PlaneCodec(Supplier<byte[]> stampSource) implements NavCodec<ClusterKey, ClusterLegs> {

        private static final long HEADER = 16;

        @Override
        public byte[] stamp() {
            return stampSource.get();
        }

        @Override
        public int keyBytes(ClusterKey key) {
            return 9;
        }

        @Override
        public void writeKey(ClusterKey key, MemorySegment into) {
            into.set(LegCells.Mapped.INT, 0, key.cluster());
            into.set(ValueLayout.JAVA_BYTE, 4, (byte) (key.toNodes() ? 1 : 0));
            into.set(LegCells.Mapped.INT, 5, key.dangerSig());
        }

        @Override
        public ClusterKey readKey(MemorySegment from) {
            return new ClusterKey(
                    from.get(LegCells.Mapped.INT, 0),
                    from.get(ValueLayout.JAVA_BYTE, 4) != 0,
                    from.get(LegCells.Mapped.INT, 5));
        }

        @Override
        public long valueBytes(ClusterLegs value) {
            return HEADER + value.cells.byteSize();
        }

        @Override
        public void writeValue(ClusterLegs value, MemorySegment into) {
            into.set(LegCells.Mapped.INT, 0, value.xMin);
            into.set(LegCells.Mapped.INT, 4, value.yMin);
            into.set(LegCells.Mapped.INT, 8, value.nodeCount);
            into.set(LegCells.Mapped.INT, 12, value.stride);
            MemorySegment cells =
                    switch (value.cells) {
                        case LegCells.OnHeap heap -> MemorySegment.ofArray(heap.values);
                        case LegCells.Mapped mapped -> mapped.segment;
                    };
            MemorySegment.copy(cells, 0, into, HEADER, cells.byteSize());
        }

        @Override
        public ClusterLegs readValue(MemorySegment from) {
            ValueLayout.OfInt i = LegCells.Mapped.INT;
            return new ClusterLegs(
                    from.get(i, 0),
                    from.get(i, 4),
                    from.get(i, 8),
                    new LegCells.Mapped(from.asSlice(HEADER)),
                    from.get(i, 12));
        }
    }
}
