package openrsc.nav.bench;

/**
 * One settle's per-node distance, time, parent and tag, behind accessors so one workload runs
 * unchanged over arrays, a heap segment, native memory or a mapped file.
 */
interface SettledView {

    int dist(int node);

    int time(int node);

    short parent(int node);

    short tag(int node);

    void set(int node, int dist, int time, short parent, short tag);

    /** Marks every node unreached: distance and time {@link Shape#UNREACHED}, parent -1, tag 0. */
    void clear();
}
