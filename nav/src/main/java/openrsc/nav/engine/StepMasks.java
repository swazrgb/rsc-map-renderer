package openrsc.nav.engine;

import java.util.concurrent.atomic.AtomicReferenceArray;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;

/**
 * Every tile's legal single steps on the static map, as a mask in {@link ClusterEdges}' direction
 * numbering, but with the steps that leave a cluster kept: what a search that crosses clusters
 * ({@link LocalField}, {@link FlatWalk}) reads instead of asking {@link CollisionMap#canStep} eight
 * times a tile. Baked a cluster at a time on first ask and kept; safe for concurrent use.
 */
final class StepMasks {

    private final CollisionMap map;
    private final AtomicReferenceArray<short[]> clusters = new AtomicReferenceArray<>(ClusterGeometry.TOTAL_CLUSTERS);

    StepMasks(CollisionMap map) {
        this.map = map;
    }

    /** The steps out of {@code (x, y)}, which must be in bounds. */
    int at(int x, int y) {
        int c = ClusterGeometry.clusterOf(x, y);
        short[] masks = clusters.get(c);
        if (masks == null) {
            masks = bake(c);
            if (!clusters.compareAndSet(c, null, masks)) {
                masks = clusters.get(c);
            }
        }
        return masks[(y - ClusterGeometry.minY(c)) * ClusterGeometry.CLUSTER_SIZE + (x - ClusterGeometry.minX(c))];
    }

    private short[] bake(int cluster) {
        int cs = ClusterGeometry.CLUSTER_SIZE;
        int x0 = ClusterGeometry.minX(cluster);
        int y0 = ClusterGeometry.minY(cluster);
        short[] masks = new short[cs * cs];
        for (int ly = 0; ly < cs; ly++) {
            for (int lx = 0; lx < cs; lx++) {
                int x = x0 + lx;
                int y = y0 + ly;
                // A cluster's grid runs past the map's edge and its floor's last row: those tiles are none of its own.
                if (!map.inBounds(x, y) || ClusterGeometry.clusterOf(x, y) != cluster) {
                    continue;
                }
                int mask = 0;
                for (int d = 0; d < ClusterEdges.DIRS; d++) {
                    int nx = x + ClusterEdges.DX[d];
                    int ny = y + ClusterEdges.DY[d];
                    if (d != ClusterEdges.CENTRE && map.inBounds(nx, ny) && map.canStep(x, y, nx, ny, Overlays.NONE)) {
                        mask |= 1 << d;
                    }
                }
                masks[ly * cs + lx] = (short) mask;
            }
        }
        return masks;
    }
}
