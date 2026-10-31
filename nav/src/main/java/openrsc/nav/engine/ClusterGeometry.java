package openrsc.nav.engine;

import openrsc.gamedata.world.CollisionMap;

/**
 * The fixed partition of the map into square clusters of {@value #CLUSTER_SIZE} tiles, one grid per
 * floor: no cluster spans two floors. Cluster ids run floor by floor, then row by row, then column
 * by column, from 0 to {@link #TOTAL_CLUSTERS} exclusive.
 *
 * <p>The grid does not divide the map evenly. The last column runs past {@link CollisionMap#WIDTH},
 * and each floor's last row runs past {@link CollisionMap#FLOOR_HEIGHT} into the next floor. Those
 * tiles are not the cluster's ({@link #clusterOf} puts them in the next floor's clusters, or none),
 * but its bounds ({@link #maxX}, {@link #maxY}) still count them.
 */
public final class ClusterGeometry {

    public static final int CLUSTER_SIZE = 64;

    /** Bumped when a change here can change what a persisted leg plane holds. */
    static final int PLANE_VERSION = 1;

    public static final int CLUSTERS_X = (CollisionMap.WIDTH + CLUSTER_SIZE - 1) / CLUSTER_SIZE;
    public static final int CLUSTERS_PER_FLOOR_Y = (CollisionMap.FLOOR_HEIGHT + CLUSTER_SIZE - 1) / CLUSTER_SIZE;
    public static final int CLUSTERS_PER_FLOOR = CLUSTERS_X * CLUSTERS_PER_FLOOR_Y;
    public static final int TOTAL_CLUSTERS = CLUSTERS_PER_FLOOR * CollisionMap.FLOOR_COUNT;

    private ClusterGeometry() {}

    /**
     * The cluster holding tile {@code (x, y)}. A tile off the map clamps to the nearest cluster, so the
     * answer is always a valid id; whether the tile exists is the caller's question.
     */
    public static int clusterOf(int x, int y) {
        int floor = y / CollisionMap.FLOOR_HEIGHT;
        if (floor < 0) {
            floor = 0;
        }
        if (floor >= CollisionMap.FLOOR_COUNT) {
            floor = CollisionMap.FLOOR_COUNT - 1;
        }
        int intraY = y - floor * CollisionMap.FLOOR_HEIGHT;
        int cx = x / CLUSTER_SIZE;
        int cy = intraY / CLUSTER_SIZE;
        if (cx < 0) {
            cx = 0;
        } else if (cx >= CLUSTERS_X) {
            cx = CLUSTERS_X - 1;
        }
        if (cy < 0) {
            cy = 0;
        } else if (cy >= CLUSTERS_PER_FLOOR_Y) {
            cy = CLUSTERS_PER_FLOOR_Y - 1;
        }
        return floor * CLUSTERS_PER_FLOOR + cy * CLUSTERS_X + cx;
    }

    public static int floorOf(int clusterId) {
        return clusterId / CLUSTERS_PER_FLOOR;
    }

    public static int cxOf(int clusterId) {
        return (clusterId % CLUSTERS_PER_FLOOR) % CLUSTERS_X;
    }

    public static int cyOf(int clusterId) {
        return (clusterId % CLUSTERS_PER_FLOOR) / CLUSTERS_X;
    }

    public static int minX(int clusterId) {
        return cxOf(clusterId) * CLUSTER_SIZE;
    }

    /** One past the cluster's last column; may lie past the map's width. */
    public static int maxX(int clusterId) {
        return minX(clusterId) + CLUSTER_SIZE;
    }

    /** Absolute: the floor's offset included. */
    public static int minY(int clusterId) {
        return floorOf(clusterId) * CollisionMap.FLOOR_HEIGHT + cyOf(clusterId) * CLUSTER_SIZE;
    }

    /** One past the cluster's last row; may lie past the floor (see the class). */
    public static int maxY(int clusterId) {
        return minY(clusterId) + CLUSTER_SIZE;
    }
}
