package openrsc.nav.cache;

import java.util.function.ToLongFunction;

public interface NavCaches {

    <K, V> NavCache<K, V> weighed(NavStore store, ToLongFunction<? super V> bytes);

    /**
     * Whether these stores keep what they load, up to their budgets. An engine over stores that do holds what
     * it loaded only weakly itself, so a store that claims to and keeps nothing has it rebuilt after every GC.
     */
    default boolean retains() {
        return true;
    }

    static NavCaches none() {
        return NoNavCaches.INSTANCE;
    }
}
