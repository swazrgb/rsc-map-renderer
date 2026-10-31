package openrsc.nav.bench;

import java.io.IOException;
import java.lang.foreign.MemorySegment;

/** How a pool of settles is stored. */
public enum SettledImpl {
    /** {@link ArraySettled}, read through its fields rather than {@link SettledView}. */
    ARRAY_DIRECT,
    /** {@link ArraySettled} behind {@link SettledView}: what the accessor alone costs. */
    ARRAY_VIEW,
    HEAP_PLANAR,
    HEAP_PACKED,
    NATIVE_PACKED,
    MAPPED_PLANAR,
    MAPPED_PACKED,
    /**
     * {@link #ARRAY_VIEW} and {@link #MAPPED_PACKED} alternating, so the same call sites see two
     * receiver types, as when a heap tier and a mapped tier serve one pool.
     */
    MIXED;

    /** {@code count} settles in this layout, holding the same numbers for the same seed. */
    SettledView[] pool(int count, long seed) throws IOException {
        long bytes = count * Shape.SETTLED_BYTES;
        MemorySegment region =
                switch (this) {
                    case NATIVE_PACKED -> Regions.offHeap(bytes);
                    case MAPPED_PLANAR, MAPPED_PACKED, MIXED -> Regions.mapped("settled", bytes);
                    default -> null;
                };
        SettledView[] pool = new SettledView[count];
        for (int i = 0; i < count; i++) {
            long base = i * Shape.SETTLED_BYTES;
            pool[i] = switch (this) {
                case ARRAY_DIRECT, ARRAY_VIEW -> new ArraySettled();
                case HEAP_PLANAR -> new PlanarSettled(Regions.heap(Shape.SETTLED_BYTES), 0);
                case HEAP_PACKED -> new PackedSettled(Regions.heap(Shape.SETTLED_BYTES), 0);
                case NATIVE_PACKED, MAPPED_PACKED -> new PackedSettled(region, base);
                case MAPPED_PLANAR -> new PlanarSettled(region, base);
                case MIXED -> i % 2 == 0 ? new ArraySettled() : new PackedSettled(region, base);
            };
            Workloads.fill(pool[i], seed, i);
        }
        if (region != null && region.isMapped()) {
            region.force(); // written back now, so no flush runs during the measurement
        }
        return pool;
    }
}
