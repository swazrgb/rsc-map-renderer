package openrsc.nav.cache;

import java.util.function.ToLongFunction;

/**
 * Makes the engine's stores, one per {@link NavStore}. The engine asks for each once, when it is
 * built, and keeps what it is given; so this decides how planning memory is kept:
 * {@link CaffeineNavCaches} on the JVM, {@link MappedNavCaches} with the leg planes in files,
 * {@link BoundedNavCaches} with neither Caffeine nor files, or {@link #none()}.
 */
public interface NavCaches {

    /** A store bounded by this host's budget for {@code store}, each value priced by {@code bytes}. */
    <K, V> NavCache<K, V> weighed(NavStore store, ToLongFunction<? super V> bytes);

    /**
     * A {@link #weighed} store whose entries {@code codec} can write out and read back, for an
     * implementation that keeps them outside the heap. The default ignores {@code codec}.
     */
    default <K, V> NavCache<K, V> persisted(NavStore store, ToLongFunction<? super V> bytes, NavCodec<K, V> codec) {
        return weighed(store, bytes);
    }

    /** Whether a value put in these stores can ever be read back; false for {@link #none()}. */
    default boolean retains() {
        return true;
    }

    /**
     * Stores that keep nothing: every ask runs its loader, a put is dropped and the stats stay
     * {@link NavCacheStats#NONE}. Every plan then pays for its metrics and searches in full.
     */
    static NavCaches none() {
        return NoNavCaches.INSTANCE;
    }
}
