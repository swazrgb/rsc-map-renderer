package openrsc.nav.cache;

import java.lang.foreign.MemorySegment;

/**
 * How one store's keys and values become bytes, for a store kept outside the heap
 * ({@link MappedNavCaches}). Other implementations ignore it.
 *
 * <p>A key must encode to the same bytes in every run: keys read back from disk after a restart are
 * matched against fresh ones. {@link #readValue} returns a view over the stored bytes, not a copy, so
 * a value read back is valid only while its store is open.
 */
public interface NavCodec<K, V> {

    /**
     * What every stored value was computed from: the inputs and the code that computed them. A store
     * whose recorded stamp differs deletes all its entries when it opens.
     */
    byte[] stamp();

    int keyBytes(K key);

    /** Writes {@code key} into a segment of exactly {@link #keyBytes} bytes. */
    void writeKey(K key, MemorySegment into);

    K readKey(MemorySegment from);

    long valueBytes(V value);

    /** Writes {@code value} into a segment of exactly {@link #valueBytes} bytes. */
    void writeValue(V value, MemorySegment into);

    /** The value {@code from} holds, as a view over it. */
    V readValue(MemorySegment from);
}
