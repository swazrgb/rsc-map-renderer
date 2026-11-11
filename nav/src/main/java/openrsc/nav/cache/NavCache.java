package openrsc.nav.cache;

import java.util.function.Function;

public interface NavCache<K, V> {

    V getIfPresent(K key);

    V get(K key, Function<? super K, ? extends V> loader);

    void put(K key, V value);

    void invalidate(K key);

    NavCacheStats stats();
}
