package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.transports.AllTransports;
import openrsc.nav.transports.Banks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LegPlaneSweepParityTest {

    private static final int CLUSTERS_TO_CHECK = 12;
    private static final int DANGER_CLUSTERS_TO_CHECK = 4;
    private static final int MIN_NODES = 2;
    private static final int SENTINEL = 0x5EED;

    private static CollisionMap map;
    private static ClusterGraph graph;
    private static DangerField danger;

    private static final Map<Integer, List<int[]>> WALKABLE = new HashMap<>();

    @BeforeAll
    static void loadWorld() {
        ServerData env = RealDefs.serverData();
        map = env.collisionMap();
        List<Transport> transports = AllTransports.defaultRegistry(env);
        graph = ClusterGraph.build(map, transports, Banks.on(env));

        DangerMap dangerMap = DangerMap.build(env.npcSpawns(), env::npc, map);
        // Rung 0 charges every aggressive spawn's tiles; 100 is the scale danger avoidance prices with.
        danger = new DangerField(dangerMap, 0, 100);
    }

    @Test
    @DisplayName("a built plane answers costsAtAny exactly as the targeted sweep does, both channels")
    void planeAgreesWithSweep() {
        Run r = sweepClusters(null, CLUSTERS_TO_CHECK);

        assertEquals(CLUSTERS_TO_CHECK, r.checked, "clusters with real node content");
        assertTrue(r.comparisons >= CLUSTERS_TO_CHECK * 6, "queries compared: " + r.comparisons);
        assertTrue(r.finite > 0, "every probed tile reached no node at all — fixture is vacuous");
    }

    @Test
    @DisplayName("with danger on, the plane's TIME channel pairs with the same tile the sweep's does")
    void dangeredPlaneAgreesWithSweep() {
        assertTrue(danger.anyDanger(), "no aggressive spawns loaded — the dangered fixture would prove nothing");
        Run r = sweepClusters(danger, DANGER_CLUSTERS_TO_CHECK);

        assertTrue(r.checked > 0, "no cluster had both danger and node content to test");
        assertTrue(r.comparisons >= r.checked * 6, "queries compared: " + r.comparisons);
        // Unless weight and time came apart, a time paired with another tile's weight would read correct.
        assertTrue(r.diverged > 0, "danger never separated time from weight — the pairing is still untested");
    }

    private static final class Run {
        int checked;
        int comparisons;
        int finite;
        int diverged;
    }

    private Run sweepClusters(DangerField field, int target) {
        Run r = new Run();
        for (int cluster = 0; cluster < ClusterGeometry.TOTAL_CLUSTERS && r.checked < target; cluster++) {
            if (graph.clusterNodeCount(cluster) < MIN_NODES) {
                continue;
            }
            // Legs treats such a cluster as undangered, so it would only repeat the undangered case.
            if (field != null && field.clusterPenalty(cluster) == 0) {
                continue;
            }
            List<int[]> tiles = walkableTiles(cluster);
            if (tiles.size() < 3) {
                continue;
            }
            r.checked++;
            int nodeCount = graph.clusterNodeCount(cluster);

            int[] a = tiles.get(0);
            int[] b = tiles.get(tiles.size() / 2);
            int[] c = tiles.get(tiles.size() - 1);

            for (boolean toNodes : new boolean[] {true, false}) {
                Legs warm = warmed(cluster, nodeCount, toNodes, field);
                compare(r, warm, cluster, nodeCount, toNodes, new int[] {a[0]}, new int[] {a[1]}, 1, field);
                compare(r, warm, cluster, nodeCount, toNodes, new int[] {b[0]}, new int[] {b[1]}, 1, field);
                compare(
                        r,
                        warm,
                        cluster,
                        nodeCount,
                        toNodes,
                        new int[] {a[0], b[0], c[0]},
                        new int[] {a[1], b[1], c[1]},
                        3,
                        field);
                r.finite += countFinite(
                        warm.costsAtAny(cluster, new int[] {a[0]}, new int[] {a[1]}, 1, toNodes, field, null));
            }
        }
        return r;
    }

    private void compare(
            Run r,
            Legs warm,
            int cluster,
            int nodeCount,
            boolean toNodes,
            int[] xs,
            int[] ys,
            int count,
            DangerField field) {
        // A fresh Legs has no plane and sweeps; warm, asked before, reads its plane.
        Legs cold = new Legs(map, graph, new CaffeineNavCaches(RealDefs.CACHE_BUDGET), () -> new byte[0]);
        int[] coldTimes = new int[nodeCount];
        int[] coldCosts = cold.costsAtAny(cluster, xs, ys, count, toNodes, field, coldTimes);
        assertTrue(cold.sweeps() > 0, "cold Legs did not sweep — the fixture is not testing two paths");

        int[] warmTimes = new int[nodeCount];
        int[] warmCosts = warm.costsAtAny(cluster, xs, ys, count, toNodes, field, warmTimes);

        String where = "cluster " + cluster + " toNodes=" + toNodes + " count=" + count + " danger=" + (field != null)
                + " at " + Arrays.toString(xs) + "/" + Arrays.toString(ys);
        assertArrayEquals(coldCosts, warmCosts, "WEIGHT channel disagrees, " + where);

        for (int i = 0; i < nodeCount; i++) {
            // An unreachable node's time differs between the two (the sweep leaves MAX_VALUE, the plane 0), and
            // no reader looks at a time whose weight is MAX_VALUE.
            if (warmCosts[i] == Integer.MAX_VALUE) {
                continue;
            }
            assertEquals(coldTimes[i], warmTimes[i], "TIME channel disagrees at node " + i + ", " + where);
            if (field == null) {
                assertEquals(
                        warmCosts[i], warmTimes[i], "undangered time must equal weight at node " + i + ", " + where);
            } else if (warmTimes[i] != warmCosts[i]) {
                r.diverged++;
            }
        }

        if (nodeCount > 1) {
            int[] shortTimes = new int[nodeCount - 1];
            int[] longTimes = new int[nodeCount + 2];
            Arrays.fill(longTimes, SENTINEL);
            warm.costsAtAny(cluster, xs, ys, count, toNodes, field, shortTimes);
            warm.costsAtAny(cluster, xs, ys, count, toNodes, field, longTimes);
            for (int i = 0; i < nodeCount - 1; i++) {
                assertEquals(warmTimes[i], shortTimes[i], "short buffer diverged at " + i + ", " + where);
                assertEquals(warmTimes[i], longTimes[i], "long buffer diverged at " + i + ", " + where);
            }
            assertEquals(
                    SENTINEL, longTimes[nodeCount], "wrote past the node count into the caller's buffer, " + where);
        }
        r.comparisons++;
    }

    private Legs warmed(int cluster, int nodeCount, boolean toNodes, DangerField field) {
        Legs legs = new Legs(map, graph, new CaffeineNavCaches(RealDefs.CACHE_BUDGET), () -> new byte[0]);
        int[] seed = walkableTiles(cluster).get(0);
        for (int i = 0; i < nodeCount + 4 && legs.planeBuilds() == 0; i++) {
            legs.costsAtAny(cluster, new int[] {seed[0]}, new int[] {seed[1]}, 1, toNodes, field, null);
        }
        assertTrue(
                legs.planeBuilds() > 0, "plane never published for cluster " + cluster + " — warm path not under test");
        return legs;
    }

    private static List<int[]> walkableTiles(int cluster) {
        return WALKABLE.computeIfAbsent(cluster, c -> {
            ClusterEdges edges = ClusterEdges.build(map, c);
            int cs = ClusterGeometry.CLUSTER_SIZE;
            int xMin = ClusterGeometry.minX(c);
            int yMin = ClusterGeometry.minY(c);
            List<int[]> out = new ArrayList<>();
            for (int ly = 0; ly < cs; ly++) {
                for (int lx = 0; lx < cs; lx++) {
                    if (edges.out[ly * cs + lx] != 0) {
                        out.add(new int[] {xMin + lx, yMin + ly});
                    }
                }
            }
            return out;
        });
    }

    private static int countFinite(int[] costs) {
        int n = 0;
        for (int cost : costs) {
            if (cost != Integer.MAX_VALUE) {
                n++;
            }
        }
        return n;
    }
}
