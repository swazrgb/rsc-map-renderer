package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every baked edge against the map, derived again independently of the engine: the leg sweeps and
 * the leg planes both read these tables, so their own cross-check cannot catch a bad bake.
 */
class ClusterEdgesParityTest {

    // Every 7th cluster: all four floors, without paying for all of them.
    private static final int STRIDE = 7;
    // A cluster this walkable is real content, not empty water.
    private static final int POPULATED_CELLS = 512;

    @Test
    @DisplayName("baked out/in masks reproduce checkAdjacent and the server's diagonal pricing exactly")
    void bakedTablesMatchTheCollisionMap() {
        CollisionMap map = RealDefs.collisionMap();
        int cs = ClusterGeometry.CLUSTER_SIZE;
        int populated = 0;
        int checkedEdges = 0;

        for (int cluster = 0; cluster < ClusterGeometry.TOTAL_CLUSTERS; cluster += STRIDE) {
            ClusterEdges edges = ClusterEdges.build(map, cluster);
            int xMin = ClusterGeometry.minX(cluster);
            int yMin = ClusterGeometry.minY(cluster);
            int walkable = 0;

            for (int ly = 0; ly < cs; ly++) {
                for (int lx = 0; lx < cs; lx++) {
                    int l = ly * cs + lx;
                    int x = xMin + lx;
                    int y = yMin + ly;
                    if (edges.out[l] != 0) {
                        walkable++;
                    }
                    for (int d = 0; d < ClusterEdges.DIRS; d++) {
                        if (d == ClusterEdges.CENTRE) {
                            continue;
                        }
                        int nlx = lx + ClusterEdges.DX[d];
                        int nly = ly + ClusterEdges.DY[d];
                        boolean inCluster = nlx >= 0 && nlx < cs && nly >= 0 && nly < cs;
                        if (!inCluster) {
                            if (bit(edges.out[l], d) || bit(edges.in[l], d)) {
                                fail("edge leaves cluster " + cluster + " at (" + x + "," + y + ") dir " + d);
                            }
                            continue;
                        }
                        int nx = xMin + nlx;
                        int ny = yMin + nly;
                        assertMatches(
                                edges.out[l], d, ClusterEdges.canStep(map, x, y, nx, ny), "out", cluster, x, y, nx, ny);
                        assertMatches(
                                edges.in[l], d, ClusterEdges.canStep(map, nx, ny, x, y), "in", cluster, x, y, nx, ny);
                        checkedEdges += 2;
                    }
                }
            }
            if (walkable >= POPULATED_CELLS) {
                populated++;
            }
        }

        // Not vacuous: an all-blocked map would pass every assertion above.
        assertTrue(populated > 20, "clusters with real walkable content: " + populated);
        assertTrue(checkedEdges > 1_000_000, "edges checked: " + checkedEdges);
    }

    private static void assertMatches(
            int mask, int d, boolean expected, String which, int cluster, int x, int y, int nx, int ny) {
        boolean actual = bit(mask, d);
        if (actual != expected) {
            assertEquals(
                    expected,
                    actual,
                    which + " edge (" + x + "," + y + ")->(" + nx + "," + ny + ") in cluster " + cluster);
        }
    }

    private static boolean bit(int mask, int index) {
        return (mask & (1 << index)) != 0;
    }
}
