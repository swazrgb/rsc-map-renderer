package openrsc.nav.cache;

import java.util.function.Function;

/**
 * A store the engine memoises into. Every value put here must be recomputable from its key alone, so
 * an implementation may drop any entry at any time, or keep none, and a miss only means "compute
 * it".
 *
 * <p>Implementations are safe for concurrent use. A {@code null} value is never kept.
 */
public interface NavCache<K, V> {

    /** The value held for {@code key}, or {@code null}; never computes one. */
    V getIfPresent(K key);

    /**
     * The value held for {@code key}, else {@code loader}'s result, kept unless it is {@code null}.
     * Whether threads missing one key together share one loader run depends on the implementation.
     * {@code loader} must not use this store: some implementations run it under a per-key lock.
     */
    V get(K key, Function<? super K, ? extends V> loader);

    /** Keeps {@code value}, which is not {@code null}, for {@code key}, replacing what was held. */
    void put(K key, V value);

    void invalidate(K key);

    NavCacheStats stats();
}
