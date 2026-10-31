package openrsc.nav.bench;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/** How a pool of leg planes is stored. */
public enum PlaneImpl {
    /** {@link ArrayPlane}, read through its fields rather than {@link PlaneView}. */
    ARRAY_DIRECT,
    ARRAY_VIEW,
    ARRAY_CELL_MAJOR,
    HEAP_PLANAR,
    MAPPED_PLANAR,
    MAPPED_PACKED,
    MAPPED_CELL_MAJOR;

    /** {@code count} planes in this layout, holding the same numbers for the same seed. */
    PlaneView[] pool(int count, long seed) throws IOException {
        long bytes = count * Shape.PLANE_BYTES;
        MemorySegment region =
                switch (this) {
                    case MAPPED_PLANAR, MAPPED_PACKED, MAPPED_CELL_MAJOR -> Regions.mapped("planes", bytes);
                    default -> null;
                };
        PlaneView[] pool = new PlaneView[count];
        for (int i = 0; i < count; i++) {
            long base = i * Shape.PLANE_BYTES;
            pool[i] = switch (this) {
                case ARRAY_DIRECT, ARRAY_VIEW -> new ArrayPlane();
                case ARRAY_CELL_MAJOR -> new ArrayCellMajorPlane();
                case HEAP_PLANAR -> new SegmentPlanes.Planar(Regions.heap(Shape.PLANE_BYTES), 0);
                case MAPPED_PLANAR -> new SegmentPlanes.Planar(region, base);
                case MAPPED_PACKED -> new SegmentPlanes.Packed(region, base);
                case MAPPED_CELL_MAJOR -> new SegmentPlanes.CellMajor(region, base);
            };
            Workloads.fill(pool[i], seed, i);
        }
        if (region != null) {
            region.force();
        }
        return pool;
    }
}
