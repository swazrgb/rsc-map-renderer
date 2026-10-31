package openrsc.nav.bench;

/**
 * One cluster's leg plane, a cost and a time from each tile to each node, behind accessors so one
 * workload runs over any storage. {@code cell} is the cluster-local tile {@code ly * 64 + lx}.
 */
interface PlaneView {

    int cost(int node, int cell);

    int time(int node, int cell);

    void set(int node, int cell, int cost, int time);
}
