package openrsc.nav.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;

/**
 * Byte-budgeted stores that need only the JDK, for a runtime without Caffeine such as a TeaVM
 * build: each is a least-recently-used map that evicts from its cold end once what it holds passes
 * its {@link NavCacheBudget} figure. A value priced above the whole figure is not kept.
 *
 * <p>Each store is guarded by one lock, which a loader runs outside: a loader may ask the same
 * store, and threads missing one key together may each run it. The first result kept is the one
 * every later ask gets.
 */
public final class BoundedNavCaches implements NavCaches {

    private final NavCacheBudget budget;

    public BoundedNavCaches(NavCacheBudget budget) {
        this.budget = budget;
    }

    @Override
    public <K, V> NavCache<K, V> weighed(NavStore store, ToLongFunction<? super V> bytes) {
        long limit =
                switch (store) {
                    case METRICS -> budget.metricBytes();
                    case GOAL_LABELS -> budget.goalLabelBytes();
                    case CLUSTER_LEGS -> budget.legBytes();
                };
        return new Store<>(limit, bytes);
    }

    private static final class Store<K, V> implements NavCache<K, V> {
        private final long limit;
        private final ToLongFunction<? super V> weigher;
        // Access order: the eldest entry is the least recently used.
        private final LinkedHashMap<K, Held<V>> map = new LinkedHashMap<>(64, 0.75f, true);

        private long held;
        private long hits;
        private long misses;
        private long loads;
        private long loadNanos;
        private long evicted;

        Store(long limit, ToLongFunction<? super V> weigher) {
            this.limit = limit;
            this.weigher = weigher;
        }

        private record Held<V>(V value, long bytes) {}

        @Override
        public synchronized V getIfPresent(K key) {
            Held<V> h = map.get(key);
            if (h == null) {
                misses++;
                return null;
            }
            hits++;
            return h.value();
        }

        @Override
        public V get(K key, Function<? super K, ? extends V> loader) {
            synchronized (this) {
                Held<V> h = map.get(key);
                if (h != null) {
                    hits++;
                    return h.value();
                }
                misses++;
            }
            long t0 = System.nanoTime();
            V value = loader.apply(key);
            long spent = System.nanoTime() - t0;
            synchronized (this) {
                loads++;
                loadNanos += spent;
                Held<V> raced = map.get(key);
                if (raced != null) {
                    return raced.value();
                }
                admit(key, value);
            }
            return value;
        }

        @Override
        public synchronized void put(K key, V value) {
            admit(key, value);
        }

        @Override
        public synchronized void invalidate(K key) {
            Held<V> old = map.remove(key);
            if (old != null) {
                held -= old.bytes();
            }
        }

        @Override
        public synchronized NavCacheStats stats() {
            return new NavCacheStats(map.size(), held, hits, misses, loads, loadNanos, evicted);
        }

        private void admit(K key, V value) {
            if (value == null) {
                return;
            }
            long bytes = Math.max(0, weigher.applyAsLong(value));
            if (bytes > limit) {
                return;
            }
            Held<V> old = map.put(key, new Held<>(value, bytes));
            if (old != null) {
                held -= old.bytes();
            }
            held += bytes;
            Iterator<Map.Entry<K, Held<V>>> eldest = map.entrySet().iterator();
            while (held > limit && eldest.hasNext()) {
                Map.Entry<K, Held<V>> e = eldest.next();
                if (e.getKey().equals(key)) {
                    continue;
                }
                held -= e.getValue().bytes();
                evicted += e.getValue().bytes();
                eldest.remove();
            }
        }
    }
}
