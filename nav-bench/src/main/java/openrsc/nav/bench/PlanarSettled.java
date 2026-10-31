package openrsc.nav.bench;

import static openrsc.nav.bench.Shape.INT;
import static openrsc.nav.bench.Shape.NODES;
import static openrsc.nav.bench.Shape.SHORT;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

/** The arrays' layout in one segment slice: every distance, then every time, parent and tag. */
final class PlanarSettled implements SettledView {

    private static final long TIME = 4L * NODES;
    private static final long PARENT = 8L * NODES;
    private static final long TAG = 10L * NODES;
    private static final MemorySegment CLEARED = cleared();

    private final MemorySegment seg;
    private final long base;

    PlanarSettled(MemorySegment seg, long base) {
        this.seg = seg;
        this.base = base;
    }

    @Override
    public int dist(int node) {
        return seg.get(INT, base + 4L * node);
    }

    @Override
    public int time(int node) {
        return seg.get(INT, base + TIME + 4L * node);
    }

    @Override
    public short parent(int node) {
        return seg.get(SHORT, base + PARENT + 2L * node);
    }

    @Override
    public short tag(int node) {
        return seg.get(SHORT, base + TAG + 2L * node);
    }

    @Override
    public void set(int node, int dist, int time, short parent, short tag) {
        seg.set(INT, base + 4L * node, dist);
        seg.set(INT, base + TIME + 4L * node, time);
        seg.set(SHORT, base + PARENT + 2L * node, parent);
        seg.set(SHORT, base + TAG + 2L * node, tag);
    }

    @Override
    public void clear() {
        MemorySegment.copy(CLEARED, 0, seg, base, Shape.SETTLED_BYTES);
    }

    private static MemorySegment cleared() {
        MemorySegment t = Arena.global().allocate(Shape.SETTLED_BYTES);
        for (int n = 0; n < NODES; n++) {
            t.set(INT, 4L * n, Shape.UNREACHED);
            t.set(INT, TIME + 4L * n, Shape.UNREACHED);
            t.set(SHORT, PARENT + 2L * n, (short) -1);
            t.set(SHORT, TAG + 2L * n, (short) 0);
        }
        return t;
    }
}
