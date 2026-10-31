package openrsc.nav.bench;

import java.util.Arrays;

/** Four parallel arrays, one per channel. */
final class ArraySettled implements SettledView {

    final int[] dist = new int[Shape.NODES];
    final int[] time = new int[Shape.NODES];
    final short[] parent = new short[Shape.NODES];
    final short[] tag = new short[Shape.NODES];

    @Override
    public int dist(int node) {
        return dist[node];
    }

    @Override
    public int time(int node) {
        return time[node];
    }

    @Override
    public short parent(int node) {
        return parent[node];
    }

    @Override
    public short tag(int node) {
        return tag[node];
    }

    @Override
    public void set(int node, int dist, int time, short parent, short tag) {
        this.dist[node] = dist;
        this.time[node] = time;
        this.parent[node] = parent;
        this.tag[node] = tag;
    }

    @Override
    public void clear() {
        Arrays.fill(dist, Shape.UNREACHED);
        Arrays.fill(time, Shape.UNREACHED);
        Arrays.fill(parent, (short) -1);
        Arrays.fill(tag, (short) 0);
    }
}
