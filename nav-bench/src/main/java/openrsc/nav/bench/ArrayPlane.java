package openrsc.nav.bench;

/** Node-major cost and time arrays: each node's 4096 tiles in a row. */
final class ArrayPlane implements PlaneView {

    final int[] grids = new int[Shape.CLUSTER_NODES * Shape.CELLS];
    final int[] times = new int[Shape.CLUSTER_NODES * Shape.CELLS];

    @Override
    public int cost(int node, int cell) {
        return grids[node * Shape.CELLS + cell];
    }

    @Override
    public int time(int node, int cell) {
        return times[node * Shape.CELLS + cell];
    }

    @Override
    public void set(int node, int cell, int cost, int time) {
        grids[node * Shape.CELLS + cell] = cost;
        times[node * Shape.CELLS + cell] = time;
    }
}
