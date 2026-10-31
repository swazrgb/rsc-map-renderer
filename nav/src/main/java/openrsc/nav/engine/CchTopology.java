package openrsc.nav.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import openrsc.gamedata.LoadProgress;
import openrsc.gamedata.world.CollisionMap;

/**
 * The metric-independent half of a customizable contraction hierarchy over a {@link ClusterGraph}:
 * which node pairs carry an edge, the order nodes are contracted in, and the shortcuts that order
 * implies. {@link CchMetric} weighs it for one routing class; {@link CchSearch} queries it.
 *
 * <p>The nodes are the graph's and one cast source per teleport region ({@link #firstSource}). An
 * intra-cluster arc is dropped where a third node of its cluster implies it, weight then time,
 * under the plain matrix and every danger field's: its replacement costs no more. The order is
 * nested dissection by InertialFlow, balanced vertex separators found by max-flow across a
 * projection and ranked above both halves; contraction then joins each node's higher neighbours.
 *
 * <p>Input arcs are numbered intra-cluster (by cluster), boundary, transport, regional teleport
 * (source to landing), cast entry (node to source). Every per-node array is indexed by rank, bar
 * {@link #rankOf}. Immutable.
 */
final class CchTopology {

    static final int NO_PARENT = -1;

    final ClusterGraph graph;
    final int nodeCount;
    /**
     * The first cast source's node id, one past the graph's last node. Arcs run into a source from its
     * cast-entry nodes, weightless, and out of it to each of its region's teleport landings.
     */
    final int firstSource;

    final int[] rankOf;
    final int[] nodeAt;
    /** Per rank, its elimination-tree parent, its lowest higher neighbour; {@link #NO_PARENT} at a root. */
    final int[] parent;
    /**
     * Per rank, its first upward edge: edges {@code firstUp[r]} to {@code firstUp[r + 1]} have tail
     * {@code r}, their heads ascending. An edge's id is its position here.
     */
    final int[] firstUp;

    /** Per edge, its higher rank; {@link #tail} holds the lower. */
    final int[] head;

    final int[] tail;
    /** Per rank, its first entry in {@link #downEdge}, which lists the edges whose head each rank is. */
    final int[] firstDown;

    final int[] downEdge;
    /**
     * Ranks grouped by elimination-tree height: every lower neighbour of a group's ranks is in an
     * earlier group, so one group's edges can be finalized in parallel.
     */
    final int[][] levels;

    /** Per cluster, its first intra arc; the next cluster's first ends its run. */
    final int[] intraFirst;
    /** Per intra arc, its cell of the cluster's matrix. */
    final int[] intraCell;
    /** Per intra arc, its slot: {@code edge << 1}, the low bit set when it runs head to tail. */
    final int[] intraSlot;

    final int[] boundarySlot;
    final int[] transportSlot;
    /** Per transport arc, its transport; {@link #transportLanding} holds its landing node. */
    final int[] transportIdx;

    final int[] transportLanding;
    final int[] anywhereSlot;
    /** Per regional teleport arc, its transport; {@link #anywhereLanding} holds its landing node. */
    final int[] anywhereIdx;

    final int[] anywhereLanding;
    /** The weightless arcs from a cast-entry node into its source. */
    final int[] castEntrySlot;
    /** The number of intra arcs, which is the first boundary arc's number. */
    final int boundaryArcs;

    /** Per edge, its first input arc in {@link #edgeArcs}, which lists each edge's arcs in arc order. */
    final int[] edgeArcFirst;

    final int[] edgeArcs;
    /**
     * The ranks a search seeded at any bank reaches, the union of the banks' elimination-tree
     * ancestors, ascending; {@link #bankSlot} gives each rank's index among them, or -1.
     */
    final int[] bankRanks;

    final int[] bankSlot;
    /**
     * Per edge {@code xy}, its lower triangles as pairs of edge ids {@code (vx, vy)} at
     * {@code triangles[2k]} and {@code triangles[2k + 1]}, k from {@code triangleFirst[xy]} up to
     * {@code triangleFirst[xy + 1]}: the routes x, v, y through a common lower neighbour v, in the order
     * customization relaxes them.
     */
    final int[] triangleFirst;

    final int[] triangles;
    /** Per transport, whether its usability reads anything of the account. */
    final boolean[] gated;

    final int[] intraArcCluster;
    /**
     * Per edge, its first entry in {@link #usedBy}, which lists the triangles each edge is one of the two
     * lower edges of; {@link #triangleOwner} names each triangle's edge.
     */
    final int[] usedByFirst;

    final int[] usedBy;
    final int[] triangleOwner;
    /** Per transport, its first arc in {@link #transportArcs}, which lists each one's hop and teleport arcs. */
    final int[] transportArcFirst;

    final int[] transportArcs;

    final long buildNanosPrune;
    final long buildNanosOrder;
    final long buildNanosContract;

    private CchTopology(Builder b) {
        this.graph = b.graph;
        this.nodeCount = b.n;
        this.firstSource = b.firstSource;
        this.rankOf = b.rankOf;
        this.nodeAt = b.nodeAt;
        this.parent = b.parent;
        this.firstUp = b.firstUp;
        this.head = b.head;
        this.tail = b.tail;
        this.firstDown = b.firstDown;
        this.downEdge = b.downEdge;
        this.levels = b.levels;
        this.intraFirst = b.intraFirst;
        this.intraCell = b.intraCell;
        this.intraSlot = b.intraSlot;
        this.boundarySlot = b.boundarySlot;
        this.transportSlot = b.transportSlot;
        this.transportIdx = b.transportIdx;
        this.transportLanding = b.transportLanding;
        this.anywhereSlot = b.anywhereSlot;
        this.anywhereIdx = b.anywhereIdx;
        this.anywhereLanding = b.anywhereLanding;
        this.castEntrySlot = b.castEntrySlot;
        this.boundaryArcs = b.intraCell.length;
        this.edgeArcFirst = b.edgeArcFirst;
        this.edgeArcs = b.edgeArcs;
        this.bankRanks = b.bankRanks;
        this.bankSlot = b.bankSlot;
        this.triangleFirst = b.triangleFirst;
        this.triangles = b.triangles;
        this.gated = b.gated;
        this.intraArcCluster = b.intraArcCluster;
        this.usedByFirst = b.usedByFirst;
        this.usedBy = b.usedBy;
        this.triangleOwner = b.triangleOwner;
        this.transportArcFirst = b.transportArcFirst;
        this.transportArcs = b.transportArcs;
        this.buildNanosPrune = b.nanosPrune;
        this.buildNanosOrder = b.nanosOrder;
        this.buildNanosContract = b.nanosContract;
    }

    int edgeCount() {
        return head.length;
    }

    int arcCount() {
        return boundaryArcs + boundarySlot.length + transportSlot.length + anywhereSlot.length + castEntrySlot.length;
    }

    boolean isSource(int v) {
        return v >= firstSource;
    }

    /** Input arc {@code a}'s slot ({@link #intraSlot}). */
    int arcSlot(int a) {
        int k = a - boundaryArcs;
        if (k < 0) {
            return intraSlot[a];
        }
        if (k < boundarySlot.length) {
            return boundarySlot[k];
        }
        k -= boundarySlot.length;
        if (k < transportSlot.length) {
            return transportSlot[k];
        }
        k -= transportSlot.length;
        return k < anywhereSlot.length ? anywhereSlot[k] : castEntrySlot[k - anywhereSlot.length];
    }

    /**
     * Input arc {@code a}'s chain tag: {@link Engine#TAG_INTRA}, {@link Engine#TAG_BOUNDARY} or its
     * transport's index. A cast entry is TAG_INTRA: it moves nowhere, and a chain drops the source.
     */
    int arcTag(int a) {
        int k = a - boundaryArcs;
        if (k < 0) {
            return Engine.TAG_INTRA;
        }
        if (k < boundarySlot.length) {
            return Engine.TAG_BOUNDARY;
        }
        k -= boundarySlot.length;
        if (k < transportSlot.length) {
            return transportIdx[k];
        }
        k -= transportSlot.length;
        return k < anywhereSlot.length ? anywhereIdx[k] : Engine.TAG_INTRA;
    }

    int intraClusterOf(int a) {
        return intraArcCluster[a];
    }

    /**
     * The topology of {@code graph}. {@code fields} must hold every danger field a metric may be
     * customized under, since an arc is dropped only where no matrix of theirs needs it.
     */
    static CchTopology build(ClusterGraph graph, List<DangerField> fields, boolean[] gated) {
        return new CchTopology(new Builder(graph, fields, gated));
    }

    String describe() {
        int n = nodeCount;
        int[] depth = new int[n];
        long depthSum = 0;
        int depthMax = 0;
        for (int r = n - 1; r >= 0; r--) {
            depth[r] = parent[r] == NO_PARENT ? 0 : depth[parent[r]] + 1;
            depthSum += depth[r];
            depthMax = Math.max(depthMax, depth[r]);
        }
        long searchArcs = 0;
        long triangles = 0;
        for (int x = 0; x < n; x++) {
            for (int i = firstDown[x]; i < firstDown[x + 1]; i++) {
                int vx = downEdge[i];
                triangles += firstUp[tail[vx] + 1] - vx - 1;
            }
        }
        long[] below = new long[n];
        for (int r = n - 1; r >= 0; r--) {
            long deg = (long) firstUp[r + 1] - firstUp[r];
            below[r] = deg + (parent[r] == NO_PARENT ? 0 : below[parent[r]]);
            searchArcs += below[r];
        }
        return String.format(
                "cch: %d nodes, %d edges, %d triangles, %d levels; elimination tree "
                        + "depth mean=%.1f max=%d, upward search arcs mean=%.0f; built prune=%dms order=%dms "
                        + "contract=%dms; input arcs intra=%d boundary=%d transport=%d teleport=%d cast-entry=%d",
                n,
                edgeCount(),
                triangles,
                levels.length,
                (double) depthSum / n,
                depthMax,
                (double) searchArcs / n,
                buildNanosPrune / 1_000_000,
                buildNanosOrder / 1_000_000,
                buildNanosContract / 1_000_000,
                intraSlot.length,
                boundarySlot.length,
                transportSlot.length,
                anywhereSlot.length,
                castEntrySlot.length);
    }

    private static final class Builder {
        final ClusterGraph graph;
        final int n;
        final int firstSource;
        int[] rankOf;
        int[] nodeAt;
        int[] parent;
        int[] firstUp;
        int[] head;
        int[] tail;
        int[] firstDown;
        int[] downEdge;
        int[][] levels;
        int[] intraFirst;
        int[] intraCell;
        int[] intraSlot;
        int[] boundarySlot;
        int[] transportSlot;
        int[] transportIdx;
        int[] transportLanding;
        int[] anywhereSlot;
        int[] anywhereIdx;
        int[] anywhereLanding;
        int[] castEntrySlot;
        int[] edgeArcFirst;
        int[] edgeArcs;
        int[] bankRanks;
        int[] bankSlot;
        int[] triangleFirst;
        int[] triangles;
        boolean[] gated;
        int[] intraArcCluster;
        int[] usedByFirst;
        int[] usedBy;
        int[] triangleOwner;
        int[] transportArcFirst;
        int[] transportArcs;
        long nanosPrune;
        long nanosOrder;
        long nanosContract;

        Builder(ClusterGraph graph, List<DangerField> fields, boolean[] gated) {
            this.graph = graph;
            this.gated = gated;
            int real = graph.totalNodes();
            this.n = real + graph.castSourceCount();
            this.firstSource = real;

            long t0 = System.nanoTime();
            LoadProgress.stage("pricing danger across the map's clusters");
            int[][] kept = keptIntraCells(graph, fields);
            nanosPrune = System.nanoTime() - t0;

            LongOpenHashSet edges = new LongOpenHashSet();
            for (int c = 0; c < kept.length; c++) {
                int first = graph.clusterFirstNode(c);
                int k = graph.clusterNodeCount(c);
                for (int cell : kept[c]) {
                    addEdge(edges, first + cell / k, first + cell % k);
                }
            }
            for (int u = 0; u < real; u++) {
                for (int v : graph.outBoundary(u)) {
                    addEdge(edges, u, v);
                }
                int[] ts = graph.outTransports(u);
                for (int i = 0; i < ts.length; i += 2) {
                    addEdge(edges, u, ts[i + 1]);
                }
            }
            for (int a = 0; a < graph.anywhereCount(); a++) {
                addEdge(edges, firstSource + graph.anywhereSource(a), graph.anywhereDestNode(a));
            }
            for (int s = 0; s < graph.castSourceCount(); s++) {
                for (int v : graph.castEntryNodes(s)) {
                    addEdge(edges, v, firstSource + s);
                }
            }

            t0 = System.nanoTime();
            LoadProgress.stage("ordering the routing hierarchy");
            order(edges, real);
            nanosOrder = System.nanoTime() - t0;

            t0 = System.nanoTime();
            LoadProgress.stage("contracting the routing hierarchy");
            contract(edges);
            inputArcs(kept);
            bankRanks();
            triangleLists();
            intraArcClusters();
            triangleUsers();
            transportArcIndex();
            nanosContract = System.nanoTime() - t0;
        }

        private static void addEdge(LongOpenHashSet edges, int a, int b) {
            if (a != b) {
                edges.add((long) Math.min(a, b) << 32 | Math.max(a, b));
            }
        }

        private static int[][] keptIntraCells(ClusterGraph graph, List<DangerField> fields) {
            int[][] out = new int[ClusterGeometry.TOTAL_CLUSTERS][];
            Parallel.forEach(out.length, c -> {
                int k = graph.clusterNodeCount(c);
                if (k < 2) {
                    out[c] = new int[0];
                    return;
                }
                int[] blind = graph.intraMatrix(c);
                boolean[] keep = unimplied(blind, blind, k);
                java.util.Set<int[]> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
                for (DangerField f : fields) {
                    if (f.clusterPenalty(c) == 0 || !seen.add(f.intraMatrix(graph, c))) {
                        continue;
                    }
                    boolean[] fk = unimplied(f.intraMatrix(graph, c), f.intraMatrixTimes(graph, c), k);
                    for (int i = 0; i < keep.length; i++) {
                        keep[i] |= fk[i];
                    }
                }
                IntArrayList cells = new IntArrayList();
                for (int i = 0; i < keep.length; i++) {
                    if (keep[i]) {
                        cells.add(i);
                    }
                }
                out[c] = cells.toIntArray();
            });
            return out;
        }

        static boolean[] unimplied(int[] w, int[] t, int k) {
            boolean[] out = new boolean[k * k];
            for (int i = 0; i < k; i++) {
                int row = i * k;
                for (int j = 0; j < k; j++) {
                    int ij = row + j;
                    if (i == j || w[ij] == Integer.MAX_VALUE) {
                        continue;
                    }
                    boolean implied = false;
                    for (int m = 0; m < k; m++) {
                        if (m == i || m == j) {
                            continue;
                        }
                        int im = row + m;
                        int mj = m * k + j;
                        if (w[im] == Integer.MAX_VALUE || w[mj] == Integer.MAX_VALUE) {
                            continue;
                        }
                        long sw = (long) w[im] + w[mj];
                        if (sw < w[ij] || (sw == w[ij] && (long) t[im] + t[mj] <= t[ij])) {
                            implied = true;
                            break;
                        }
                    }
                    out[ij] = !implied;
                }
            }
            return out;
        }

        private void order(LongOpenHashSet edges, int real) {
            int[] degree = new int[real];
            edges.forEach((long e) -> {
                int a = (int) (e >>> 32);
                int b = (int) e;
                if (b < real) {
                    degree[a]++;
                    degree[b]++;
                }
            });
            int[] adjFirst = new int[real + 1];
            for (int v = 0; v < real; v++) {
                adjFirst[v + 1] = adjFirst[v] + degree[v];
            }
            int[] adj = new int[adjFirst[real]];
            int[] fill = Arrays.copyOf(adjFirst, real);
            edges.forEach((long e) -> {
                int a = (int) (e >>> 32);
                int b = (int) e;
                if (b < real) {
                    adj[fill[a]++] = b;
                    adj[fill[b]++] = a;
                }
            });
            int[] px = new int[real];
            int[] py = new int[real];
            for (int v = 0; v < real; v++) {
                px[v] = graph.nodeX(v);
                // Floors stack: a ladder joins nodes at nearly the same place.
                py[v] = graph.nodeY(v) % CollisionMap.FLOOR_HEIGHT;
            }
            Dissection d = new Dissection(real, adjFirst, adj, px, py);
            int[] all = new int[real];
            for (int v = 0; v < real; v++) {
                all[v] = v;
            }
            d.dissect(all);
            int[] order = d.out.toIntArray();
            if (order.length != real) {
                throw new IllegalStateException("dissection ordered " + order.length + " of " + real);
            }
            nodeAt = Arrays.copyOf(order, n);
            for (int v = real; v < n; v++) {
                // The cast sources outrank every node.
                nodeAt[v] = v;
            }
            rankOf = new int[n];
            for (int r = 0; r < n; r++) {
                rankOf[nodeAt[r]] = r;
            }
        }

        private void contract(LongOpenHashSet edges) {
            IntOpenHashSet[] up = new IntOpenHashSet[n];
            edges.forEach((long e) -> {
                int ra = rankOf[(int) (e >>> 32)];
                int rb = rankOf[(int) e];
                int lo = Math.min(ra, rb);
                if (up[lo] == null) {
                    up[lo] = new IntOpenHashSet(4);
                }
                up[lo].add(Math.max(ra, rb));
            });
            int[][] sorted = new int[n][];
            firstUp = new int[n + 1];
            parent = new int[n];
            for (int r = 0; r < n; r++) {
                int[] u = up[r] == null ? new int[0] : up[r].toIntArray();
                up[r] = null;
                Arrays.sort(u);
                sorted[r] = u;
                firstUp[r + 1] = firstUp[r] + u.length;
                parent[r] = u.length == 0 ? NO_PARENT : u[0];
                if (u.length > 1) {
                    int p = u[0];
                    if (up[p] == null) {
                        up[p] = new IntOpenHashSet(u.length);
                    }
                    for (int i = 1; i < u.length; i++) {
                        up[p].add(u[i]);
                    }
                }
            }
            int m = firstUp[n];
            head = new int[m];
            tail = new int[m];
            int[] downCount = new int[n + 1];
            for (int r = 0; r < n; r++) {
                System.arraycopy(sorted[r], 0, head, firstUp[r], sorted[r].length);
                for (int e = firstUp[r]; e < firstUp[r + 1]; e++) {
                    tail[e] = r;
                    downCount[head[e]]++;
                }
            }
            firstDown = new int[n + 1];
            for (int r = 0; r < n; r++) {
                firstDown[r + 1] = firstDown[r] + downCount[r];
            }
            downEdge = new int[m];
            int[] fill = Arrays.copyOf(firstDown, n);
            for (int e = 0; e < m; e++) {
                downEdge[fill[head[e]]++] = e;
            }
            int[] height = new int[n];
            int maxHeight = 0;
            for (int r = 0; r < n; r++) {
                if (parent[r] != NO_PARENT) {
                    height[parent[r]] = Math.max(height[parent[r]], height[r] + 1);
                }
                maxHeight = Math.max(maxHeight, height[r]);
            }
            int[] perLevel = new int[maxHeight + 1];
            for (int r = 0; r < n; r++) {
                perLevel[height[r]]++;
            }
            levels = new int[maxHeight + 1][];
            for (int h = 0; h <= maxHeight; h++) {
                levels[h] = new int[perLevel[h]];
            }
            int[] levelFill = new int[maxHeight + 1];
            for (int r = 0; r < n; r++) {
                levels[height[r]][levelFill[height[r]]++] = r;
            }
        }

        private void transportArcIndex() {
            int transports = gated.length;
            transportArcFirst = new int[transports + 1];
            for (int t : transportIdx) {
                transportArcFirst[t + 1]++;
            }
            for (int t : anywhereIdx) {
                transportArcFirst[t + 1]++;
            }
            for (int t = 0; t < transports; t++) {
                transportArcFirst[t + 1] += transportArcFirst[t];
            }
            transportArcs = new int[transportArcFirst[transports]];
            int[] fill = Arrays.copyOf(transportArcFirst, transports);
            int firstTransportArc = intraCell.length + boundarySlot.length;
            for (int k = 0; k < transportIdx.length; k++) {
                transportArcs[fill[transportIdx[k]]++] = firstTransportArc + k;
            }
            int firstAnywhereArc = firstTransportArc + transportSlot.length;
            for (int k = 0; k < anywhereIdx.length; k++) {
                transportArcs[fill[anywhereIdx[k]]++] = firstAnywhereArc + k;
            }
        }

        private void triangleUsers() {
            int m = head.length;
            int total = triangleFirst[m];
            usedByFirst = new int[m + 1];
            for (int k = 0; k < 2 * total; k++) {
                usedByFirst[triangles[k] + 1]++;
            }
            for (int e = 0; e < m; e++) {
                usedByFirst[e + 1] += usedByFirst[e];
            }
            usedBy = new int[2 * total];
            triangleOwner = new int[total];
            int[] fill = Arrays.copyOf(usedByFirst, m);
            for (int xy = 0; xy < m; xy++) {
                for (int k = triangleFirst[xy]; k < triangleFirst[xy + 1]; k++) {
                    triangleOwner[k] = xy;
                    usedBy[fill[triangles[2 * k]]++] = k;
                    usedBy[fill[triangles[2 * k + 1]]++] = k;
                }
            }
        }

        private void intraArcClusters() {
            intraArcCluster = new int[intraCell.length];
            for (int c = 0; c < intraFirst.length - 1; c++) {
                for (int a = intraFirst[c]; a < intraFirst[c + 1]; a++) {
                    intraArcCluster[a] = c;
                }
            }
        }

        private void triangleLists() {
            int m = head.length;
            int[] slotOf = new int[n];
            triangleFirst = new int[m + 1];
            for (int pass = 0; pass < 2; pass++) {
                int[] fill = pass == 0 ? null : Arrays.copyOf(triangleFirst, m);
                for (int x = 0; x < n; x++) {
                    for (int e = firstUp[x]; e < firstUp[x + 1]; e++) {
                        slotOf[head[e]] = e;
                    }
                    for (int i = firstDown[x]; i < firstDown[x + 1]; i++) {
                        int vx = downEdge[i];
                        int end = firstUp[tail[vx] + 1];
                        for (int vy = vx + 1; vy < end; vy++) {
                            int xy = slotOf[head[vy]];
                            if (pass == 0) {
                                triangleFirst[xy + 1]++;
                            } else {
                                int k = fill[xy]++;
                                triangles[2 * k] = vx;
                                triangles[2 * k + 1] = vy;
                            }
                        }
                    }
                }
                if (pass == 0) {
                    for (int e = 0; e < m; e++) {
                        triangleFirst[e + 1] += triangleFirst[e];
                    }
                    triangles = new int[2 * triangleFirst[m]];
                }
            }
        }

        private void bankRanks() {
            boolean[] in = new boolean[n];
            int count = 0;
            for (int i = 0; i < graph.bankNodeCount(); i++) {
                for (int r = rankOf[graph.bankNode(i)]; r != NO_PARENT && !in[r]; r = parent[r]) {
                    in[r] = true;
                    count++;
                }
            }
            bankRanks = new int[count];
            bankSlot = new int[n];
            Arrays.fill(bankSlot, -1);
            for (int r = 0, k = 0; r < n; r++) {
                if (in[r]) {
                    bankSlot[r] = k;
                    bankRanks[k++] = r;
                }
            }
        }

        private int slot(int from, int to) {
            int rf = rankOf[from];
            int rt = rankOf[to];
            int lo = Math.min(rf, rt);
            int hi = Math.max(rf, rt);
            int e = Arrays.binarySearch(head, firstUp[lo], firstUp[lo + 1], hi);
            if (e < 0) {
                throw new IllegalStateException("no edge for arc " + from + "->" + to);
            }
            return e << 1 | (rf < rt ? 0 : 1);
        }

        private void inputArcs(int[][] kept) {
            int clusters = ClusterGeometry.TOTAL_CLUSTERS;
            intraFirst = new int[clusters + 1];
            for (int c = 0; c < clusters; c++) {
                intraFirst[c + 1] = intraFirst[c] + kept[c].length;
            }
            intraCell = new int[intraFirst[clusters]];
            intraSlot = new int[intraCell.length];
            for (int c = 0; c < clusters; c++) {
                int first = graph.clusterFirstNode(c);
                int k = graph.clusterNodeCount(c);
                for (int i = 0; i < kept[c].length; i++) {
                    int cell = kept[c][i];
                    intraCell[intraFirst[c] + i] = cell;
                    intraSlot[intraFirst[c] + i] = slot(first + cell / k, first + cell % k);
                }
            }
            int real = graph.totalNodes();
            IntArrayList boundary = new IntArrayList();
            IntArrayList tSlot = new IntArrayList();
            IntArrayList tIdx = new IntArrayList();
            IntArrayList tLanding = new IntArrayList();
            for (int u = 0; u < real; u++) {
                for (int v : graph.outBoundary(u)) {
                    if (u != v) {
                        boundary.add(slot(u, v));
                    }
                }
                int[] ts = graph.outTransports(u);
                for (int i = 0; i < ts.length; i += 2) {
                    int v = ts[i + 1];
                    // A hop back onto its own node never shortens a route.
                    if (u == v) {
                        continue;
                    }
                    tSlot.add(slot(u, v));
                    tIdx.add(ts[i]);
                    tLanding.add(v);
                }
            }
            boundarySlot = boundary.toIntArray();
            transportSlot = tSlot.toIntArray();
            transportIdx = tIdx.toIntArray();
            transportLanding = tLanding.toIntArray();
            int anywhereCount = graph.anywhereCount();
            anywhereSlot = new int[anywhereCount];
            anywhereIdx = new int[anywhereCount];
            anywhereLanding = new int[anywhereCount];
            for (int a = 0; a < anywhereCount; a++) {
                anywhereSlot[a] = slot(firstSource + graph.anywhereSource(a), graph.anywhereDestNode(a));
                anywhereIdx[a] = graph.anywhereTransportIdx(a);
                anywhereLanding[a] = graph.anywhereDestNode(a);
            }
            IntArrayList castEntries = new IntArrayList();
            for (int s = 0; s < graph.castSourceCount(); s++) {
                for (int v : graph.castEntryNodes(s)) {
                    castEntries.add(slot(v, firstSource + s));
                }
            }
            castEntrySlot = castEntries.toIntArray();
            int[][] slots = {intraSlot, boundarySlot, transportSlot, anywhereSlot, castEntrySlot};
            edgeArcFirst = new int[head.length + 1];
            for (int[] kind : slots) {
                for (int slot : kind) {
                    edgeArcFirst[(slot >>> 1) + 1]++;
                }
            }
            for (int e = 0; e < head.length; e++) {
                edgeArcFirst[e + 1] += edgeArcFirst[e];
            }
            edgeArcs = new int[edgeArcFirst[head.length]];
            int[] fill = Arrays.copyOf(edgeArcFirst, head.length);
            int arc = 0;
            for (int[] kind : slots) {
                for (int slot : kind) {
                    edgeArcs[fill[slot >>> 1]++] = arc++;
                }
            }
        }
    }

    /**
     * Nested dissection by InertialFlow: project the nodes onto a direction, take the first and last
     * quarter as source and sink, and cut them apart with the fewest nodes. Four directions are tried
     * and the smallest cut wins; it is ranked above both sides, each dissected in turn, one connected
     * component at a time.
     */
    private static final class Dissection {
        private static final int LEAF = 4;
        private static final int INF = Integer.MAX_VALUE / 4;
        private static final int[][] DIRECTIONS = {{1, 0}, {0, 1}, {1, 1}, {1, -1}};

        final int[] adjFirst;
        final int[] adj;
        final int[] px;
        final int[] py;
        final int[] stamp;
        final int[] local;
        int generation;
        final IntArrayList out = new IntArrayList();

        Dissection(int n, int[] adjFirst, int[] adj, int[] px, int[] py) {
            this.adjFirst = adjFirst;
            this.adj = adj;
            this.px = px;
            this.py = py;
            this.stamp = new int[n];
            this.local = new int[n];
        }

        void dissect(int[] nodes) {
            List<int[]> components = components(nodes);
            if (components.size() > 1) {
                for (int[] c : components) {
                    dissect(c);
                }
                return;
            }
            if (nodes.length <= LEAF) {
                out.addElements(out.size(), nodes);
                return;
            }
            int[] separator = separator(nodes);
            int member = ++generation;
            for (int v : separator) {
                stamp[v] = member;
            }
            int[] rest = new int[nodes.length - separator.length];
            int k = 0;
            for (int v : nodes) {
                if (stamp[v] != member) {
                    rest[k++] = v;
                }
            }
            dissect(rest);
            out.addElements(out.size(), separator);
        }

        private List<int[]> components(int[] nodes) {
            int member = ++generation;
            for (int v : nodes) {
                stamp[v] = member;
            }
            int seen = ++generation;
            List<int[]> out = new ArrayList<>();
            int[] queue = new int[nodes.length];
            for (int s : nodes) {
                if (stamp[s] != member) {
                    continue;
                }
                int headIdx = 0;
                int tailIdx = 0;
                queue[tailIdx++] = s;
                stamp[s] = seen;
                while (headIdx < tailIdx) {
                    int u = queue[headIdx++];
                    for (int i = adjFirst[u]; i < adjFirst[u + 1]; i++) {
                        int w = adj[i];
                        if (stamp[w] == member) {
                            stamp[w] = seen;
                            queue[tailIdx++] = w;
                        }
                    }
                }
                out.add(Arrays.copyOf(queue, tailIdx));
            }
            return out;
        }

        private int[] separator(int[] nodes) {
            int s = nodes.length;
            int member = ++generation;
            for (int i = 0; i < s; i++) {
                stamp[nodes[i]] = member;
                local[nodes[i]] = i;
            }
            int[] lFirst = new int[s + 1];
            for (int i = 0; i < s; i++) {
                int u = nodes[i];
                int deg = 0;
                for (int a = adjFirst[u]; a < adjFirst[u + 1]; a++) {
                    if (stamp[adj[a]] == member) {
                        deg++;
                    }
                }
                lFirst[i + 1] = lFirst[i] + deg;
            }
            int[] lAdj = new int[lFirst[s]];
            for (int i = 0, k = 0; i < s; i++) {
                int u = nodes[i];
                for (int a = adjFirst[u]; a < adjFirst[u + 1]; a++) {
                    if (stamp[adj[a]] == member) {
                        lAdj[k++] = local[adj[a]];
                    }
                }
            }
            int[] best = null;
            int bestBalance = -1;
            long[] keys = new long[s];
            int quarter = Math.max(1, s / 4);
            for (int[] dir : DIRECTIONS) {
                for (int i = 0; i < s; i++) {
                    long proj = (long) dir[0] * px[nodes[i]] + (long) dir[1] * py[nodes[i]];
                    keys[i] = proj << 32 | i;
                }
                Arrays.sort(keys);
                byte[] role = new byte[s];
                for (int i = 0; i < quarter; i++) {
                    role[(int) keys[i]] = 1;
                    role[(int) keys[s - 1 - i]] = 2;
                }
                int[] cut = minVertexCut(s, lFirst, lAdj, role, best == null ? INF : best.length);
                if (cut == null) {
                    continue;
                }
                int sepSize = cut[0];
                int balance = cut[1];
                if (best == null || sepSize < best.length || (sepSize == best.length && balance > bestBalance)) {
                    best = new int[sepSize];
                    for (int i = 0; i < sepSize; i++) {
                        best[i] = nodes[cut[2 + i]];
                    }
                    bestBalance = balance;
                }
            }
            if (best == null) {
                // Every direction put a source beside a sink: take the best-connected node and go on.
                int top = 0;
                for (int i = 1; i < s; i++) {
                    if (lFirst[i + 1] - lFirst[i] > lFirst[top + 1] - lFirst[top]) {
                        top = i;
                    }
                }
                best = new int[] {nodes[top]};
            }
            return best;
        }

        /**
         * {@code {cutSize, smallerSide, cutNodes...}} in local ids, or null when source and sink touch or
         * the cut would reach {@code limit}.
         */
        private static int[] minVertexCut(int s, int[] lFirst, int[] lAdj, byte[] role, int limit) {
            int nodes = 2 * s + 2;
            int source = 2 * s;
            int sink = 2 * s + 1;
            int arcs = 2 * (s + lAdj.length + s);
            int[] to = new int[arcs];
            int[] cap = new int[arcs];
            int[] next = new int[arcs];
            int[] first = new int[nodes];
            Arrays.fill(first, -1);
            int[] count = {0};
            for (int v = 0; v < s; v++) {
                int in = 2 * v;
                int outNode = in + 1;
                // Unit capacity for terminals too: a node that joins a source straight to a sink belongs in the cut.
                arc(to, cap, next, first, count, in, outNode, 1);
                if (role[v] == 1) {
                    arc(to, cap, next, first, count, source, in, INF);
                } else if (role[v] == 2) {
                    arc(to, cap, next, first, count, outNode, sink, INF);
                }
                for (int a = lFirst[v]; a < lFirst[v + 1]; a++) {
                    arc(to, cap, next, first, count, outNode, 2 * lAdj[a], INF);
                }
            }
            int[] prevArc = new int[nodes];
            int[] visit = new int[nodes];
            int[] queue = new int[nodes];
            int round = 0;
            int flow = 0;
            while (true) {
                round++;
                int qh = 0;
                int qt = 0;
                queue[qt++] = source;
                visit[source] = round;
                boolean found = false;
                while (qh < qt && !found) {
                    int u = queue[qh++];
                    for (int a = first[u]; a != -1; a = next[a]) {
                        int w = to[a];
                        if (cap[a] > 0 && visit[w] != round) {
                            visit[w] = round;
                            prevArc[w] = a;
                            if (w == sink) {
                                found = true;
                                break;
                            }
                            queue[qt++] = w;
                        }
                    }
                }
                if (!found) {
                    break;
                }
                int bottleneck = INF;
                for (int w = sink; w != source; w = to[prevArc[w] ^ 1]) {
                    bottleneck = Math.min(bottleneck, cap[prevArc[w]]);
                }
                if (bottleneck >= INF) {
                    return null;
                }
                for (int w = sink; w != source; w = to[prevArc[w] ^ 1]) {
                    cap[prevArc[w]] -= bottleneck;
                    cap[prevArc[w] ^ 1] += bottleneck;
                }
                flow += bottleneck;
                if (flow >= limit) {
                    return null;
                }
            }
            int[] out = new int[2 + flow];
            int k = 2;
            int sourceSide = 0;
            for (int v = 0; v < s; v++) {
                boolean inReached = visit[2 * v] == round;
                boolean outReached = visit[2 * v + 1] == round;
                if (inReached && !outReached) {
                    out[k++] = v;
                } else if (outReached) {
                    sourceSide++;
                }
            }
            if (k != 2 + flow) {
                throw new IllegalStateException("cut of " + (k - 2) + " nodes for a flow of " + flow);
            }
            out[0] = flow;
            out[1] = Math.min(sourceSide, s - sourceSide - flow);
            return out;
        }

        private static void arc(int[] to, int[] cap, int[] next, int[] first, int[] count, int u, int v, int c) {
            int a = count[0];
            to[a] = v;
            cap[a] = c;
            next[a] = first[u];
            first[u] = a;
            to[a + 1] = u;
            cap[a + 1] = 0;
            next[a + 1] = first[v];
            first[v] = a + 1;
            count[0] = a + 2;
        }
    }
}
