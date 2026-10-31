package openrsc.nav.engine;

import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;

/**
 * One cluster's walk graph, baked once from the static map: per cell, a mask of the neighbours a
 * single step reaches. Bit {@code d} is the neighbour at {@code (DX[d], DY[d])}, so
 * {@code d = (dy + 1) * 3 + (dx + 1)}, {@link #CENTRE} is never set and {@code 8 - d} is the
 * opposite direction. Steps that leave the cluster are cleared, so a set bit always names a cell of
 * the same grid, {@link #STEP}{@code [d]} cells away, and a sweep needs no bounds test.
 */
final class ClusterEdges {

    static final int DIRS = 9;
    static final int CENTRE = 4;
    static final int FEASIBLE_MASK = 0x1FF;

    /** Bumped when a change here can change what a persisted leg plane holds. */
    static final int PLANE_VERSION = 1;

    static final int[] DX = {-1, 0, 1, -1, 0, 1, -1, 0, 1};
    static final int[] DY = {-1, -1, -1, 0, 0, 0, 1, 1, 1};

    /** Each direction's offset between cell indices of a cluster's row-major grid. */
    static final int[] STEP = new int[DIRS];

    static {
        for (int d = 0; d < DIRS; d++) {
            STEP[d] = DY[d] * ClusterGeometry.CLUSTER_SIZE + DX[d];
        }
    }

    /** Per cell, row-major: bit {@code d} when the step from it to its neighbour at {@code d} is legal. */
    final int[] out;
    /** Per cell: bit {@code d} when its neighbour at {@code d} can step into it. */
    final int[] in;

    private ClusterEdges(int[] out, int[] in) {
        this.out = out;
        this.in = in;
    }

    static ClusterEdges build(CollisionMap map, int cluster) {
        int cs = ClusterGeometry.CLUSTER_SIZE;
        int cells = cs * cs;
        int xMin = ClusterGeometry.minX(cluster);
        int yMin = ClusterGeometry.minY(cluster);
        int[] out = new int[cells];
        int[] in = new int[cells];

        for (int ly = 0; ly < cs; ly++) {
            for (int lx = 0; lx < cs; lx++) {
                int l = ly * cs + lx;
                int x = xMin + lx;
                int y = yMin + ly;
                int mask = 0;
                for (int d = 0; d < DIRS; d++) {
                    if (d == CENTRE) {
                        continue;
                    }
                    int nlx = lx + DX[d];
                    int nly = ly + DY[d];
                    if (nlx < 0 || nlx >= cs || nly < 0 || nly >= cs) {
                        continue;
                    }
                    if (!canStep(map, x, y, xMin + nlx, yMin + nly)) {
                        continue;
                    }
                    mask |= 1 << d;
                }
                out[l] = mask;
            }
        }

        // The transpose of out, not a second pass over the map.
        for (int l = 0; l < cells; l++) {
            int lx = l % cs;
            int ly = l / cs;
            int mask = 0;
            for (int d = 0; d < DIRS; d++) {
                if (d == CENTRE) {
                    continue;
                }
                int nlx = lx + DX[d];
                int nly = ly + DY[d];
                if (nlx < 0 || nlx >= cs || nly < 0 || nly >= cs) {
                    continue;
                }
                int opposite = 8 - d;
                int nOut = out[nly * cs + nlx];
                if ((nOut & (1 << opposite)) == 0) {
                    continue;
                }
                mask |= 1 << d;
            }
            in[l] = mask;
        }
        return new ClusterEdges(out, in);
    }

    /** {@link CollisionMap#canStep} with no overlay and no occupancy: the answer every mask is baked from. */
    static boolean canStep(CollisionMap map, int ax, int ay, int bx, int by) {
        return map.canStep(ax, ay, bx, by, Overlays.NONE, null);
    }
}
