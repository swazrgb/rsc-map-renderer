package openrsc.nav.cache;

public record NavCacheStats(
        long entries, long heldBytes, long hits, long misses, long loads, long loadNanos, long evictedBytes) {

    public static final NavCacheStats NONE = new NavCacheStats(0, 0, 0, 0, 0, 0, 0);

    public double hitRate() {
        long asks = hits + misses;
        return asks == 0 ? 1.0 : (double) hits / asks;
    }

    public double missCostMs() {
        return loads == 0 ? 0.0 : loadNanos / (double) loads / 1_000_000.0;
    }
}
