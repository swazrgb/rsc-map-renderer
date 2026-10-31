package openrsc.nav.cache;

/**
 * One store's counters at the moment they were read. Byte figures are the sum of what the store's
 * weigher said each entry costs, not measured heap.
 *
 * @param heldBytes bytes held, per the weigher; for a file-backed store, the chunk space written
 *     (replaced entries included) plus its in-memory overflow
 * @param hits asks answered from the store, by {@code get} and {@code getIfPresent} alike
 * @param misses asks it could not answer
 * @param loads loader runs, including those that returned {@code null}
 * @param loadNanos time spent inside loaders, all runs together, in nanoseconds
 * @param evictedBytes bytes dropped to stay within budget, per the weigher
 */
public record NavCacheStats(
        long entries, long heldBytes, long hits, long misses, long loads, long loadNanos, long evictedBytes) {

    /** All zero: what a store that keeps and counts nothing reports. */
    public static final NavCacheStats NONE = new NavCacheStats(0, 0, 0, 0, 0, 0, 0);

    /** Hits over asks, in [0, 1]; 1.0 before the first ask. */
    public double hitRate() {
        long asks = hits + misses;
        return asks == 0 ? 1.0 : (double) hits / asks;
    }

    /** The mean time of one loader run, in milliseconds; 0 before the first. */
    public double missCostMs() {
        return loads == 0 ? 0.0 : loadNanos / (double) loads / 1_000_000.0;
    }
}
