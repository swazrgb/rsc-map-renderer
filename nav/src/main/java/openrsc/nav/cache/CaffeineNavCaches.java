package openrsc.nav.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Stores backed by Caffeine, each bounded by its {@link NavCacheBudget} figure and recording stats.
 * Threads missing one key together run its loader once and share the result.
 */
public final class CaffeineNavCaches implements NavCaches {

    private final NavCacheBudget budget;

    public CaffeineNavCaches(NavCacheBudget budget) {
        this.budget = Objects.requireNonNull(budget, "budget");
    }

    @Override
    public <K, V> NavCache<K, V> weighed(NavStore store, ToLongFunction<? super V> bytes) {
        Cache<K, V> cache = Caffeine.newBuilder()
                .maximumWeight(bytesFor(store))
                .<K, V>weigher((k, v) -> (int) Math.min(Integer.MAX_VALUE, bytes.applyAsLong(v)))
                .recordStats()
                .build();
        return new Store<>(cache, true);
    }

    private long bytesFor(NavStore store) {
        return switch (store) {
            case METRICS -> budget.metricBytes();
            case GOAL_LABELS -> budget.goalLabelBytes();
            case CLUSTER_LEGS -> budget.legBytes();
        };
    }

    private record Store<K, V>(Cache<K, V> cache, boolean weighed) implements NavCache<K, V> {

        @Override
        public V getIfPresent(K key) {
            return cache.getIfPresent(key);
        }

        @Override
        public V get(K key, Function<? super K, ? extends V> loader) {
            return cache.get(key, loader);
        }

        @Override
        public void put(K key, V value) {
            cache.put(key, value);
        }

        @Override
        public void invalidate(K key) {
            cache.invalidate(key);
        }

        @Override
        public NavCacheStats stats() {
            CacheStats s = cache.stats();
            long held = weighed
                    ? cache.policy()
                            .eviction()
                            .map(e -> e.weightedSize().orElse(0L))
                            .orElse(0L)
                    : 0L;
            return new NavCacheStats(
                    cache.estimatedSize(),
                    held,
                    s.hitCount(),
                    s.missCount(),
                    s.loadCount(),
                    s.totalLoadTime(),
                    weighed ? s.evictionWeight() : 0L);
        }
    }
}
