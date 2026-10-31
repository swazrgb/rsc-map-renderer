package openrsc.bot.api.cache;

import java.util.Map;

/**
 * Player-cache values published for an account, as booleans and ints keyed by the server's own
 * cache key names. A key that is absent is one nobody tracks for this account; each reader picks
 * the default that suits its question, so none is built in.
 */
public record CacheValues(Map<String, Boolean> bools, Map<String, Integer> ints) {

    public static final CacheValues EMPTY = new CacheValues(Map.of(), Map.of());

    public CacheValues {
        bools = Map.copyOf(bools);
        ints = Map.copyOf(ints);
    }

    /** The boolean under {@code key}, or {@code dflt} when it is not tracked. */
    public boolean bool(String key, boolean dflt) {
        Boolean v = bools.get(key);
        return v == null ? dflt : v;
    }

    /** The int under {@code key}, or {@code dflt} when it is not tracked. */
    public int intValue(String key, int dflt) {
        Integer v = ints.get(key);
        return v == null ? dflt : v;
    }

    /** Whether {@code key} is tracked, as a boolean or an int. */
    public boolean tracks(String key) {
        return bools.containsKey(key) || ints.containsKey(key);
    }
}
