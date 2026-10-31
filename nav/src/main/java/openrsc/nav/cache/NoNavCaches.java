package openrsc.nav.cache;

import java.util.function.Function;
import java.util.function.ToLongFunction;

final class NoNavCaches implements NavCaches {

    static final NoNavCaches INSTANCE = new NoNavCaches();

    private NoNavCaches() {}

    @Override
    public <K, V> NavCache<K, V> weighed(NavStore store, ToLongFunction<? super V> bytes) {
        return new Nothing<>();
    }

    @Override
    public boolean retains() {
        return false;
    }

    private static final class Nothing<K, V> implements NavCache<K, V> {

        @Override
        public V getIfPresent(K key) {
            return null;
        }

        @Override
        public V get(K key, Function<? super K, ? extends V> loader) {
            return loader.apply(key);
        }

        @Override
        public void put(K key, V value) {}

        @Override
        public void invalidate(K key) {}

        @Override
        public NavCacheStats stats() {
            return NavCacheStats.NONE;
        }
    }
}
