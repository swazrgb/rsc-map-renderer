package openrsc.nav.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import java.util.List;
import openrsc.bot.api.Nullable;

/**
 * A {@link Routing} that searches the graph as it is: every input arc, Dijkstra from the seeds, a node's rank its
 * own number. Arcs are numbered intra-cluster (by cluster, every ordered pair of distinct nodes), boundary,
 * transport, regional teleport (source to landing), cast entry (node to source, weightless). An edge is an arc for
 * a forward half and {@code arcCount + arc} for a backward one, so that its {@link #tail} is the end toward the
 * seeds either way.
 */
final class PlainRouting implements Routing {

    final ClusterGraph graph;
    final int nodeCount;
    final int firstSource;
    final int arcCount;
    final int[] from;
    final int[] to;
    /** Per arc, its {@link Engine#TAG_INTRA}, {@link Engine#TAG_BOUNDARY} or transport index. */
    final int[] tag;

    final int intraArcs;
    final int boundaryEnd;
    final int transportEnd;
    final int anywhereEnd;
    /** Per intra arc, its cluster and its cell of the cluster's matrix. */
    final int[] intraCluster;

    final int[] intraCell;
    /** Per transport or teleport arc, its transport. */
    final int[] transportOf;

    /** Out-arcs of each node: {@code outArc[outFirst[v]]} up to {@code outArc[outFirst[v + 1]]}, in arc order. */
    final int[] outFirst;

    final int[] outArc;
    final int[] inFirst;
    final int[] inArc;

    private final int[] bankSlots;
    private final long[] bankLabels;

    private PlainRouting(ClusterGraph graph) {
        this.graph = graph;
        int real = graph.totalNodes();
        this.nodeCount = real + graph.castSourceCount();
        this.firstSource = real;
        IntArrayList f = new IntArrayList();
        IntArrayList t = new IntArrayList();
        IntArrayList tags = new IntArrayList();
        IntArrayList cluster = new IntArrayList();
        IntArrayList cell = new IntArrayList();
        for (int c = 0; c < ClusterGeometry.TOTAL_CLUSTERS; c++) {
            int first = graph.clusterFirstNode(c);
            int k = graph.clusterNodeCount(c);
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) {
                    if (i != j) {
                        f.add(first + i);
                        t.add(first + j);
                        tags.add(Engine.TAG_INTRA);
                        cluster.add(c);
                        cell.add(i * k + j);
                    }
                }
            }
        }
        this.intraArcs = f.size();
        for (int u = 0; u < real; u++) {
            for (int v : graph.outBoundary(u)) {
                if (u != v) {
                    f.add(u);
                    t.add(v);
                    tags.add(Engine.TAG_BOUNDARY);
                }
            }
        }
        this.boundaryEnd = f.size();
        IntArrayList transports = new IntArrayList();
        for (int u = 0; u < real; u++) {
            int[] ts = graph.outTransports(u);
            for (int i = 0; i < ts.length; i += 2) {
                if (u != ts[i + 1]) {
                    f.add(u);
                    t.add(ts[i + 1]);
                    tags.add(ts[i]);
                    transports.add(ts[i]);
                }
            }
        }
        this.transportEnd = f.size();
        for (int a = 0; a < graph.anywhereCount(); a++) {
            f.add(firstSource + graph.anywhereSource(a));
            t.add(graph.anywhereDestNode(a));
            tags.add(graph.anywhereTransportIdx(a));
            transports.add(graph.anywhereTransportIdx(a));
        }
        this.anywhereEnd = f.size();
        for (int s = 0; s < graph.castSourceCount(); s++) {
            for (int v : graph.castEntryNodes(s)) {
                f.add(v);
                t.add(firstSource + s);
                tags.add(Engine.TAG_INTRA);
            }
        }
        this.arcCount = f.size();
        this.from = f.toIntArray();
        this.to = t.toIntArray();
        this.tag = tags.toIntArray();
        this.intraCluster = cluster.toIntArray();
        this.intraCell = cell.toIntArray();
        this.transportOf = transports.toIntArray();
        this.outFirst = new int[nodeCount + 1];
        this.outArc = new int[arcCount];
        this.inFirst = new int[nodeCount + 1];
        this.inArc = new int[arcCount];
        adjacency(from, outFirst, outArc);
        adjacency(to, inFirst, inArc);

        int banks = graph.bankNodeCount();
        this.bankSlots = new int[nodeCount];
        java.util.Arrays.fill(bankSlots, -1);
        int rows = 0;
        for (int i = 0; i < banks; i++) {
            if (bankSlots[graph.bankNode(i)] < 0) {
                bankSlots[graph.bankNode(i)] = rows++;
            }
        }
        // A search labels every node it reaches, the banks' own among them, so a bank's row holds only itself.
        this.bankLabels = new long[rows * banks];
        java.util.Arrays.fill(bankLabels, PackedCost.INF);
        for (int i = 0; i < banks; i++) {
            bankLabels[bankSlots[graph.bankNode(i)] * banks + i] = 0L;
        }
    }

    private void adjacency(int[] end, int[] first, int[] arcs) {
        for (int a = 0; a < arcCount; a++) {
            first[end[a] + 1]++;
        }
        for (int v = 0; v < nodeCount; v++) {
            first[v + 1] += first[v];
        }
        int[] fill = first.clone();
        for (int a = 0; a < arcCount; a++) {
            arcs[fill[end[a]]++] = a;
        }
    }

    static Routing build(ClusterGraph graph, List<DangerField> fields, boolean[] gated, int[] baseCosts) {
        return new PlainRouting(graph);
    }

    long[] bankLabels() {
        return bankLabels;
    }

    /** The arc behind edge {@code edge} of either half. */
    int arcOf(int edge) {
        return edge < arcCount ? edge : edge - arcCount;
    }

    /** The end of edge {@code edge} away from its half's seeds. */
    int head(int edge) {
        return edge < arcCount ? to[edge] : from[edge - arcCount];
    }

    boolean isSource(int node) {
        return node >= firstSource;
    }

    @Override
    public int nodeCount() {
        return nodeCount;
    }

    @Override
    public int firstSource() {
        return firstSource;
    }

    @Override
    public int rankOf(int node) {
        return node;
    }

    @Override
    public int[] bankSlots() {
        return bankSlots;
    }

    @Override
    public int tail(int edge) {
        return edge < arcCount ? from[edge] : to[edge - arcCount];
    }

    @Override
    public Metric customize(
            DangerField danger,
            boolean[] canUse,
            int[] tCosts,
            @Nullable Metric parent,
            boolean @Nullable [] parentCanUse) {
        return new PlainMetric(this, danger, canUse, tCosts);
    }

    @Override
    public Search search() {
        return new PlainSearch(this);
    }

    @Override
    public String stats() {
        return "plain search";
    }

    @Override
    public String describe() {
        return String.format(
                "plain: %d nodes, %d arcs (intra=%d boundary=%d transport=%d teleport=%d cast-entry=%d)",
                nodeCount,
                arcCount,
                intraArcs,
                boundaryEnd - intraArcs,
                transportEnd - boundaryEnd,
                anywhereEnd - transportEnd,
                arcCount - anywhereEnd);
    }
}
