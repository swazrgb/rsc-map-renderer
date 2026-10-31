package openrsc.nav.bench;

import static openrsc.nav.bench.Shape.INT;
import static openrsc.nav.bench.Shape.NODES;
import static openrsc.nav.bench.Shape.SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/**
 * One 12-byte record per node, distance, time, parent and tag side by side, so a node's channels
 * share a cache line.
 */
final class PackedSettled implements SettledView {

    private static final long RECORD = 12;
    private static final MemorySegment CLEARED = cleared();

    private final MemorySegment seg;
    private final long base;

    PackedSettled(MemorySegment seg, long base) {
        this.seg = seg;
        this.base = base;
    }

    @Override
    public int dist(int node) {
        return seg.get(INT, base + RECORD * node);
    }

    @Override
    public int time(int node) {
        return seg.get(INT, base + RECORD * node + 4);
    }

    @Override
    public short parent(int node) {
        return seg.get(SHORT, base + RECORD * node + 8);
    }

    @Override
    public short tag(int node) {
        return seg.get(SHORT, base + RECORD * node + 10);
    }

    @Override
    public void set(int node, int dist, int time, short parent, short tag) {
        long at = base + RECORD * node;
        seg.set(INT, at, dist);
        seg.set(INT, at + 4, time);
        seg.set(SHORT, at + 8, parent);
        seg.set(SHORT, at + 10, tag);
    }

    @Override
    public void clear() {
        MemorySegment.copy(CLEARED, 0, seg, base, Shape.SETTLED_BYTES);
    }

    private static MemorySegment cleared() {
        MemorySegment t = Arena.global().allocate(Shape.SETTLED_BYTES);
        for (int n = 0; n < NODES; n++) {
            long at = RECORD * n;
            t.set(INT, at, Shape.UNREACHED);
            t.set(INT, at + 4, Shape.UNREACHED);
            t.set(SHORT, at + 8, (short) -1);
            t.set(SHORT, at + 10, (short) 0);
        }
        return t;
    }
}
