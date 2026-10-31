package openrsc.nav.engine;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

/**
 * A leg plane's ints ({@link Legs.ClusterLegs#cells}), on the heap where a plane is built or in a
 * slot of a mapped store's file, read the same either way. An interface rather than a
 * {@link MemorySegment} over both because the browser build (TeaVM) has no {@code java.lang.foreign}:
 * there a plane is only ever {@link OnHeap}.
 */
sealed interface LegCells {

    int get(int index);

    /** The bytes these ints take: what a byte-budgeted store weighs the plane by. */
    long byteSize();

    final class OnHeap implements LegCells {
        final int[] values;

        OnHeap(int[] values) {
            this.values = values;
        }

        @Override
        public int get(int index) {
            return values[index];
        }

        @Override
        public long byteSize() {
            return (long) values.length * Integer.BYTES;
        }
    }

    final class Mapped implements LegCells {
        /** Native byte order, which the store's file header records; unaligned, so a slot may start anywhere. */
        static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED;

        final MemorySegment segment;

        Mapped(MemorySegment segment) {
            this.segment = segment;
        }

        @Override
        public int get(int index) {
            return segment.getAtIndex(INT, index);
        }

        @Override
        public long byteSize() {
            return segment.byteSize();
        }
    }
}
