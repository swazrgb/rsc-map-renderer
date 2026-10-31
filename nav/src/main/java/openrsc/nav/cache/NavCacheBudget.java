package openrsc.nav.cache;

/**
 * How many bytes each in-memory store may hold, one figure per {@link NavStore}. A figure bounds the
 * sum of what the store's weigher reports, not measured heap.
 *
 * <p>The constructor throws {@link IllegalArgumentException} for a figure that is not positive: a
 * zero budget would keep nothing and make every ask a miss.
 */
public record NavCacheBudget(long metricBytes, long goalLabelBytes, long legBytes) {

    private static final long MB = 1L << 20;

    public static final NavCacheBudget DEFAULTS = new NavCacheBudget(256 * MB, 64 * MB, 1536 * MB);

    public NavCacheBudget {
        require(metricBytes, "metricBytes");
        require(goalLabelBytes, "goalLabelBytes");
        require(legBytes, "legBytes");
    }

    private static void require(long value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(
                    name + " must be positive (a zero budget admits nothing and makes every ask a miss); got " + value);
        }
    }
}
