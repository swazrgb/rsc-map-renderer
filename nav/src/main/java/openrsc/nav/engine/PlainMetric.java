package openrsc.nav.engine;

/**
 * Every arc of a {@link PlainRouting} weighed for one class, packed ({@link PackedCost}): {@link PackedCost#INF}
 * for an unusable transport or an unreachable cell.
 */
final class PlainMetric implements Metric {

    final long[] weights;
    private final long[] bankLabels;

    PlainMetric(PlainRouting r, DangerField danger, boolean[] canUse, int[] tCosts) {
        ClusterGraph graph = r.graph;
        this.weights = new long[r.arcCount];
        this.bankLabels = r.bankLabels();
        for (int a = 0; a < r.intraArcs; a++) {
            int c = r.intraCluster[a];
            int cell = r.intraCell[a];
            int w = danger.intraMatrix(graph, c)[cell];
            weights[a] = w == Integer.MAX_VALUE
                    ? PackedCost.INF
                    : PackedCost.pack(w, danger.intraMatrixTimes(graph, c)[cell]);
        }
        for (int a = r.intraArcs; a < r.boundaryEnd; a++) {
            weights[a] = PackedCost.pack(Engine.STEP_COST, Engine.STEP_COST);
        }
        for (int a = r.boundaryEnd; a < r.anywhereEnd; a++) {
            int t = r.transportOf[a - r.boundaryEnd];
            weights[a] =
                    canUse[t] ? landing(graph, danger, tCosts[t] + graph.price(t), tCosts[t], r.to[a]) : PackedCost.INF;
        }
        // Cast entries stay 0: they move nowhere.
    }

    /** A hop's cost is its time; its price and the landing's danger charge are weight only. */
    private static long landing(ClusterGraph graph, DangerField danger, int weight, int time, int node) {
        return PackedCost.pack(weight + danger.penaltyAt(graph.nodeX(node), graph.nodeY(node)), time);
    }

    @Override
    public long bytes() {
        return 16L + 8L * weights.length;
    }

    @Override
    public long[] toBankLabels() {
        return bankLabels;
    }

    @Override
    public long[] fromBankLabels() {
        return bankLabels;
    }
}
