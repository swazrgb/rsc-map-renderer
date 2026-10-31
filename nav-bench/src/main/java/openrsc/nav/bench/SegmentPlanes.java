package openrsc.nav.bench;

import static openrsc.nav.bench.Shape.CELLS;
import static openrsc.nav.bench.Shape.CLUSTER_NODES;
import static openrsc.nav.bench.Shape.INT;

import java.lang.foreign.MemorySegment;

/** Three plane layouts over a slice of a segment. */
final class SegmentPlanes {

    private SegmentPlanes() {}

    /** The arrays' layout: every node's cost grid, then every node's time grid. */
    static final class Planar implements PlaneView {

        private static final long TIMES = 4L * CLUSTER_NODES * CELLS;

        private final MemorySegment seg;
        private final long base;

        Planar(MemorySegment seg, long base) {
            this.seg = seg;
            this.base = base;
        }

        @Override
        public int cost(int node, int cell) {
            return seg.get(INT, base + 4L * (node * CELLS + cell));
        }

        @Override
        public int time(int node, int cell) {
            return seg.get(INT, base + TIMES + 4L * (node * CELLS + cell));
        }

        @Override
        public void set(int node, int cell, int cost, int time) {
            seg.set(INT, base + 4L * (node * CELLS + cell), cost);
            seg.set(INT, base + TIMES + 4L * (node * CELLS + cell), time);
        }
    }

    /** Node-major, with each tile's cost and time side by side. */
    static final class Packed implements PlaneView {

        private final MemorySegment seg;
        private final long base;

        Packed(MemorySegment seg, long base) {
            this.seg = seg;
            this.base = base;
        }

        @Override
        public int cost(int node, int cell) {
            return seg.get(INT, base + 8L * (node * CELLS + cell));
        }

        @Override
        public int time(int node, int cell) {
            return seg.get(INT, base + 8L * (node * CELLS + cell) + 4);
        }

        @Override
        public void set(int node, int cell, int cost, int time) {
            long at = base + 8L * (node * CELLS + cell);
            seg.set(INT, at, cost);
            seg.set(INT, at + 4, time);
        }
    }

    /** Cell-major, as {@link ArrayCellMajorPlane} lays it out on the heap. */
    static final class CellMajor implements PlaneView {

        private final MemorySegment seg;
        private final long base;

        CellMajor(MemorySegment seg, long base) {
            this.seg = seg;
            this.base = base;
        }

        @Override
        public int cost(int node, int cell) {
            return seg.get(INT, base + 8L * (cell * CLUSTER_NODES + node));
        }

        @Override
        public int time(int node, int cell) {
            return seg.get(INT, base + 8L * (cell * CLUSTER_NODES + node) + 4);
        }

        @Override
        public void set(int node, int cell, int cost, int time) {
            long at = base + 8L * (cell * CLUSTER_NODES + node);
            seg.set(INT, at, cost);
            seg.set(INT, at + 4, time);
        }
    }
}
