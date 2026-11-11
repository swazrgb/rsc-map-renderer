package openrsc.nav.engine;

interface LegCells {

    int get(int index);

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
}
