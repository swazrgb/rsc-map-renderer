package openrsc.nav.engine;

/** A route's weight and time in one long: the weight in the high half, so comparing two compares weight first. */
final class PackedCost {

    /** No route. Far above any sum of real costs, so adding one to it stays above them too. */
    static final long INF = 1L << 61;

    private PackedCost() {}

    /** Both in [0, 2^31). */
    static long pack(int weight, int time) {
        return (long) weight << 32 | (time & 0xFFFFFFFFL);
    }

    static int weightOf(long packed) {
        return (int) (packed >>> 32);
    }
}
