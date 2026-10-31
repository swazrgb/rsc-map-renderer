package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;
import java.util.SplittableRandom;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("one of nav's heaviest classes (0.3-0.5 GB live): alone, so its peak shares no heap")
class CchParityTest {

    private static final long INF = CchMetric.INF;

    private static Engine engine;
    private static ClusterGraph graph;
    private static CollisionMap map;

    @BeforeAll
    static void load() {
        ServerData env = RealDefs.serverData();
        engine = (Engine) RealDefs.cachedPathfinder().nav();
        graph = engine.graph();
        map = env.collisionMap();
    }

    @Test
    @DisplayName("hierarchy prices and chains match a plain Dijkstra under random metrics")
    void matchesPlainDijkstra() {
        CchTopology topo = engine.topology();
        CchSearch search = new CchSearch(topo);
        CchSearch refTile = new CchSearch(topo);
        CchSearch refBank = new CchSearch(topo);
        List<Transport> transports = graph.transports();
        int rungs = engine.dangerMap().rungOf(Integer.MAX_VALUE) + 1;
        List<String> failures = new ArrayList<>();
        int reachable = 0;
        int bankChains = 0;
        for (int metricSeed = 0; metricSeed < 8; metricSeed++) {
            SplittableRandom rnd = new SplittableRandom(metricSeed * 7919L + 1);
            DangerField danger = new DangerField(engine.dangerMap(), rnd.nextInt(rungs), rnd.nextInt(4) == 0 ? 0 : 100);
            boolean[] canUse = new boolean[transports.size()];
            int[] tCosts = new int[transports.size()];
            for (int i = 0; i < canUse.length; i++) {
                canUse[i] = rnd.nextInt(10) < 7;
                int base = Math.max(0, transports.get(i).cost());
                tCosts[i] = rnd.nextInt(4) == 0 ? base + rnd.nextInt(400) : base;
            }
            CchMetric metric = CchMetric.customize(topo, danger, canUse, tCosts, metricSeed % 2 == 1);
            int[] baseCosts = new int[transports.size()];
            boolean[] ungated = new boolean[transports.size()];
            boolean[] gatedOnly = canUse.clone();
            for (int i = 0; i < baseCosts.length; i++) {
                baseCosts[i] = Math.max(0, transports.get(i).cost());
                ungated[i] = !topo.gated[i];
                gatedOnly[i] |= !topo.gated[i];
            }
            CchMetric whole = CchMetric.customize(topo, danger, gatedOnly, baseCosts, false);
            boolean[] child = gatedOnly.clone();
            int[] flips = new int[24];
            int flipped = 0;
            for (int tries = 0; flipped < flips.length && tries < 10_000; tries++) {
                int i = rnd.nextInt(child.length);
                if (topo.gated[i] && !child[i]) {
                    child[i] = true;
                    flips[flipped++] = i;
                }
            }
            CchMetric wholeChild = CchMetric.customize(topo, danger, child, baseCosts, false);
            CchMetric changedChild =
                    CchMetric.customizeChanged(whole, danger, child, baseCosts, Arrays.copyOf(flips, flipped));
            if (!Arrays.equals(wholeChild.up, changedChild.up) || !Arrays.equals(wholeChild.down, changedChild.down)) {
                failures.add("metric " + metricSeed + ": a child customized from its parent differs");
            }
            boolean[] closed = gatedOnly.clone();
            int[] shut = new int[24];
            int shutCount = 0;
            for (int tries = 0; shutCount < shut.length && tries < 10_000; tries++) {
                int i = rnd.nextInt(closed.length);
                if (topo.gated[i] && closed[i]) {
                    closed[i] = false;
                    shut[shutCount++] = i;
                }
            }
            CchMetric wholeClosed = CchMetric.customize(topo, danger, closed, baseCosts, false);
            CchMetric changedClosed =
                    CchMetric.customizeChanged(whole, danger, closed, baseCosts, Arrays.copyOf(shut, shutCount));
            if (!Arrays.equals(wholeClosed.up, changedClosed.up)
                    || !Arrays.equals(wholeClosed.down, changedClosed.down)) {
                failures.add("metric " + metricSeed + ": a class closing its parent's transports differs");
            }
            int[] enabled = new int[gatedOnly.length];
            int enabledCount = 0;
            for (int i = 0; i < gatedOnly.length; i++) {
                if (topo.gated[i] && gatedOnly[i]) {
                    enabled[enabledCount++] = i;
                }
            }
            CchMetric fromBase = CchMetric.customizeChanged(
                    CchMetric.customize(topo, danger, ungated, baseCosts, false),
                    danger,
                    gatedOnly,
                    baseCosts,
                    Arrays.copyOf(enabled, enabledCount));
            if (!Arrays.equals(whole.up, fromBase.up) || !Arrays.equals(whole.down, fromBase.down)) {
                failures.add("metric " + metricSeed + ": change-driven from the base differs");
            }
            for (int pair = 0; pair < 120; pair++) {
                int[] from = tile(rnd);
                int[] goal = tile(rnd);
                long[] fwd = legSeeds(from, true, danger);
                long[] bwd = legSeeds(goal, false, danger);
                long want = reference(danger, canUse, tCosts, from, fwd, bwd);

                search.forward.reset();
                search.backward.reset();
                for (int v = 0; v < fwd.length; v++) {
                    if (fwd[v] != INF) {
                        search.forward.seed(v, fwd[v]);
                    }
                    if (bwd[v] != INF) {
                        search.backward.seed(v, bwd[v]);
                    }
                }
                seedCasts(topo, search.forward, from);
                search.forward.run(metric);
                search.backward.run(metric);
                long got = search.meet();
                String where =
                        String.format("metric %d, (%d,%d)->(%d,%d)", metricSeed, from[0], from[1], goal[0], goal[1]);
                bankChains += bankChainsMatch(topo, metric, search, refTile, refBank, from, fwd, rnd, where, failures);
                if (want != got) {
                    failures.add(where + ": dijkstra " + describe(want) + ", hierarchy " + describe(got));
                    continue;
                }
                if (got == INF) {
                    continue;
                }
                reachable++;
                long walked = walk(search.chain(metric), danger, canUse, tCosts, from, fwd, bwd, where, failures);
                if (walked != got) {
                    failures.add(where + ": chain walks " + describe(walked) + ", priced " + describe(got));
                }
            }
        }
        assertTrue(
                failures.isEmpty(),
                failures.size() + " failures:\n"
                        + String.join("\n", failures.subList(0, Math.min(20, failures.size()))));
        assertTrue(reachable > 300, "too few reachable pairs to prove anything: " + reachable);
        assertTrue(bankChains > 300, "too few bank legs met to prove anything: " + bankChains);
    }

    private static int bankChainsMatch(
            CchTopology topo,
            CchMetric metric,
            CchSearch search,
            CchSearch refTile,
            CchSearch refBank,
            int[] from,
            long[] fwd,
            SplittableRandom rnd,
            String where,
            List<String> failures) {
        CchSearch.Half back = search.backward;
        int kept = 0;
        int[] ranks = new int[back.size()];
        long[] costs = new long[back.size()];
        int[] preds = new int[back.size()];
        for (int k = 0; k < back.size(); k++) {
            int r = back.rankAt(k);
            if (back.dist[r] != INF) {
                ranks[kept] = r;
                costs[kept] = back.dist[r];
                preds[kept++] = back.pred[r];
            }
        }
        ranks = Arrays.copyOf(ranks, kept);
        costs = Arrays.copyOf(costs, kept);
        preds = Arrays.copyOf(preds, kept);
        refTile.forward.reset();
        for (int v = 0; v < fwd.length; v++) {
            if (fwd[v] != INF) {
                refTile.forward.seed(v, fwd[v]);
            }
        }
        seedCasts(topo, refTile.forward, from);
        refTile.forward.run(metric);
        int met = 0;
        int banks = topo.graph.bankNodeCount();
        for (int t = 0; t < 4; t++) {
            int bank = rnd.nextInt(banks);
            int node = topo.graph.bankNode(bank);
            refTile.backward.reset();
            refTile.backward.seed(node, 0L);
            refTile.backward.run(metric);
            refTile.meet();
            List<int[]> toWant = refTile.chain(metric);
            if (!sameChain(toWant, search.chainToBank(metric, bank))) {
                failures.add(where + ": the leg to bank " + bank + " read off labels differs");
            }
            refBank.forward.reset();
            refBank.forward.seed(node, 0L);
            refBank.forward.run(metric);
            List<int[]> fromWant = refBank.chainToLabels(metric, ranks, costs, preds);
            if (!sameChain(fromWant, search.chainFromBank(metric, bank, ranks, costs, preds))) {
                failures.add(where + ": the leg out of bank " + bank + " read off labels differs");
            }
            met += (toWant != null ? 1 : 0) + (fromWant != null ? 1 : 0);
        }
        return met;
    }

    private static boolean sameChain(List<int[]> a, List<int[]> b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!Arrays.equals(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static long[] legSeeds(int[] tile, boolean toNodes, DangerField danger) {
        long[] out = new long[graph.totalNodes()];
        Arrays.fill(out, INF);
        int c = ClusterGeometry.clusterOf(tile[0], tile[1]);
        int[] times = new int[graph.clusterNodeCount(c)];
        int[] costs = engine.legs().costsAt(c, tile[0], tile[1], toNodes, danger, times);
        for (int i = 0; i < costs.length; i++) {
            if (costs[i] != Integer.MAX_VALUE) {
                out[graph.clusterFirstNode(c) + i] = CchMetric.pack(costs[i], times[i]);
            }
        }
        return out;
    }

    private static void seedCasts(CchTopology topo, CchSearch.Half half, int[] from) {
        for (int s = 0; s < graph.castSourceCount(); s++) {
            if (graph.castSourceRegion(s).contains(from[0], from[1])) {
                half.seed(topo.firstSource + s, 0L);
            }
        }
    }

    private static boolean castsFrom(int u, int a) {
        for (int v : graph.castEntryNodes(graph.anywhereSource(a))) {
            if (v == u) {
                return true;
            }
        }
        return false;
    }

    private static long reference(
            DangerField danger, boolean[] canUse, int[] tCosts, int[] from, long[] fwd, long[] bwd) {
        int n = graph.totalNodes();
        long[] dist = fwd.clone();
        int[][] castsAt = new int[n][];
        for (int a = 0; a < graph.anywhereCount(); a++) {
            int t = graph.anywhereTransportIdx(a);
            int v = graph.anywhereDestNode(a);
            if (canUse[t] && graph.castSourceRegion(graph.anywhereSource(a)).contains(from[0], from[1])) {
                dist[v] = Math.min(dist[v], cast(danger, tCosts[t], v));
            }
            for (int u : graph.castEntryNodes(graph.anywhereSource(a))) {
                int[] prev = castsAt[u] == null ? new int[0] : castsAt[u];
                castsAt[u] = Arrays.copyOf(prev, prev.length + 1);
                castsAt[u][prev.length] = a;
            }
        }
        PriorityQueue<long[]> heap = new PriorityQueue<>((x, y) -> Long.compare(x[0], y[0]));
        for (int v = 0; v < n; v++) {
            if (dist[v] != INF) {
                heap.add(new long[] {dist[v], v});
            }
        }
        boolean[] done = new boolean[n];
        while (!heap.isEmpty()) {
            long[] top = heap.poll();
            int u = (int) top[1];
            if (done[u] || top[0] != dist[u]) {
                continue;
            }
            done[u] = true;
            int c = graph.nodeCluster(u);
            int first = graph.clusterFirstNode(c);
            int k = graph.clusterNodeCount(c);
            int[] w = danger.intraMatrix(graph, c);
            int[] t = danger.intraMatrixTimes(graph, c);
            for (int j = 0; j < k && w != null; j++) {
                int cell = (u - first) * k + j;
                if (first + j != u && w[cell] != Integer.MAX_VALUE) {
                    relax(dist, heap, first + j, top[0] + CchMetric.pack(w[cell], t[cell]));
                }
            }
            for (int v : graph.outBoundary(u)) {
                relax(dist, heap, v, top[0] + CchMetric.pack(Engine.STEP_COST, Engine.STEP_COST));
            }
            int[] ts = graph.outTransports(u);
            for (int i = 0; i < ts.length; i += 2) {
                if (canUse[ts[i]]) {
                    relax(dist, heap, ts[i + 1], top[0] + hop(danger, tCosts[ts[i]], ts[i + 1]));
                }
            }
            for (int a : castsAt[u] == null ? new int[0] : castsAt[u]) {
                int teleport = graph.anywhereTransportIdx(a);
                int landing = graph.anywhereDestNode(a);
                if (canUse[teleport]) {
                    relax(dist, heap, landing, top[0] + cast(danger, tCosts[teleport], landing));
                }
            }
        }
        long best = INF;
        for (int v = 0; v < n; v++) {
            if (dist[v] != INF && bwd[v] != INF) {
                best = Math.min(best, dist[v] + bwd[v]);
            }
        }
        return best;
    }

    private static void relax(long[] dist, PriorityQueue<long[]> heap, int v, long c) {
        if (c < dist[v]) {
            dist[v] = c;
            heap.add(new long[] {c, v});
        }
    }

    private static long cast(DangerField danger, int cost, int landing) {
        return hop(danger, cost, landing) + CchMetric.pack(Engine.TELEPORT_WEIGHT, 0);
    }

    private static long hop(DangerField danger, int cost, int landing) {
        return CchMetric.pack(cost + danger.penaltyAt(graph.nodeX(landing), graph.nodeY(landing)), cost);
    }

    private static long walk(
            List<int[]> chain,
            DangerField danger,
            boolean[] canUse,
            int[] tCosts,
            int[] from,
            long[] fwd,
            long[] bwd,
            String where,
            List<String> failures) {
        int[] root = chain.get(0);
        long total;
        if (root[1] == Engine.TAG_SEED) {
            total = fwd[root[0]];
        } else {
            boolean cast = false;
            for (int a = 0; a < graph.anywhereCount(); a++) {
                cast |= graph.anywhereTransportIdx(a) == root[1]
                        && graph.anywhereDestNode(a) == root[0]
                        && graph.castSourceRegion(graph.anywhereSource(a)).contains(from[0], from[1]);
            }
            if (!cast || !canUse[root[1]]) {
                failures.add(where + ": chain opens with transport " + root[1] + " that is no usable cast");
                return INF;
            }
            total = cast(danger, tCosts[root[1]], root[0]);
        }
        for (int i = 1; i < chain.size(); i++) {
            int u = chain.get(i - 1)[0];
            int v = chain.get(i)[0];
            int tag = chain.get(i)[1];
            long arc = INF;
            if (tag == Engine.TAG_INTRA && graph.nodeCluster(u) == graph.nodeCluster(v)) {
                int c = graph.nodeCluster(u);
                int k = graph.clusterNodeCount(c);
                int cell = (u - graph.clusterFirstNode(c)) * k + (v - graph.clusterFirstNode(c));
                int w = danger.intraMatrix(graph, c)[cell];
                if (w != Integer.MAX_VALUE) {
                    arc = CchMetric.pack(w, danger.intraMatrixTimes(graph, c)[cell]);
                }
            } else if (tag == Engine.TAG_BOUNDARY) {
                for (int b : graph.outBoundary(u)) {
                    if (b == v) {
                        arc = CchMetric.pack(Engine.STEP_COST, Engine.STEP_COST);
                    }
                }
            } else if (tag >= 0 && canUse[tag]) {
                int[] ts = graph.outTransports(u);
                for (int j = 0; j < ts.length; j += 2) {
                    if (ts[j] == tag && ts[j + 1] == v) {
                        arc = hop(danger, tCosts[tag], v);
                    }
                }
                for (int a = 0; a < graph.anywhereCount(); a++) {
                    if (graph.anywhereTransportIdx(a) == tag && graph.anywhereDestNode(a) == v && castsFrom(u, a)) {
                        arc = cast(danger, tCosts[tag], v);
                    }
                }
            }
            if (arc == INF) {
                failures.add(where + ": link " + u + " -> " + v + " tagged " + tag + " is no arc");
                return INF;
            }
            total += arc;
        }
        int last = chain.get(chain.size() - 1)[0];
        return bwd[last] == INF ? INF : total + bwd[last];
    }

    private static int[] tile(SplittableRandom rnd) {
        while (true) {
            int x = rnd.nextInt(CollisionMap.WIDTH);
            int floors = rnd.nextInt(10) < 7 ? 1 : CollisionMap.FLOOR_COUNT;
            int y = rnd.nextInt(CollisionMap.FLOOR_HEIGHT * floors);
            if (map.inBounds(x, y) && !map.fullyBlocked(x, y)) {
                return new int[] {x, y};
            }
        }
    }

    private static String describe(long packed) {
        return packed == INF ? "unreachable" : "(" + CchMetric.weightOf(packed) + ", " + (packed & 0xFFFFFFFFL) + ")";
    }
}
