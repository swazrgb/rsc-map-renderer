package openrsc.nav.bench;

/**
 * Cell-major on the heap: per tile, every node's cost and time side by side, so reading one tile for
 * all nodes is one contiguous run instead of {@link Shape#CLUSTER_NODES} scattered reads.
 */
final class ArrayCellMajorPlane implements PlaneView {

    final int[] data = new int[2 * Shape.CLUSTER_NODES * Shape.CELLS];

    @Override
    public int cost(int node, int cell) {
        return data[2 * (cell * Shape.CLUSTER_NODES + node)];
    }

    @Override
    public int time(int node, int cell) {
        return data[2 * (cell * Shape.CLUSTER_NODES + node) + 1];
    }

    @Override
    public void set(int node, int cell, int cost, int time) {
        int at = 2 * (cell * Shape.CLUSTER_NODES + node);
        data[at] = cost;
        data[at + 1] = time;
    }
}
