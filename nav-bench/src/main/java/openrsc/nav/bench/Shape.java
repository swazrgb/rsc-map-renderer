package openrsc.nav.bench;

import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * The sizes the storage benchmarks model, so each one touches as many bytes, as far apart, as a
 * populated world graph would: 8398 nodes, 17 banks, and the mean of 25 nodes in a 64 by 64 tile
 * cluster.
 */
final class Shape {

    static final int NODES = 8398;
    /** The nodes in one cluster: a leg-1 vector's length and a plane's node count. */
    static final int CLUSTER_NODES = 25;

    static final int BANKS = 17;
    /** Transports castable from anywhere that one tile pricing scans; an assumed count. */
    static final int ANYWHERE = 24;

    static final int CELLS = 64 * 64;

    /** One settle: an int distance, an int time, a short parent and a short tag per node. */
    static final long SETTLED_BYTES = 12L * NODES;
    /** One leg plane: an int cost and an int time per node per tile of the cluster. */
    static final long PLANE_BYTES = 8L * CLUSTER_NODES * CELLS;

    static final int UNREACHED = Integer.MAX_VALUE;

    /**
     * Little-endian so a mapped file's layout does not depend on the host; unaligned because a packed
     * record puts ints and shorts at offsets no alignment covers.
     */
    static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private Shape() {}
}
