package openrsc.bot.api;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.AbstractCollection;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import org.jetbrains.annotations.Nullable;

final class ChunkMap<V> {

    private final int slots;
    private final Long2ObjectOpenHashMap<Chunk<V>> chunks = new Long2ObjectOpenHashMap<>();
    private int size;
    private @Nullable Frozen<V> frozen;

    ChunkMap(int slots) {
        this.slots = slots;
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private int local(int x, int z, int slot) {
        return ((((z & 7) << 3) | (x & 7)) * slots) + slot;
    }

    @Nullable
    V get(int x, int z, int slot) {
        Chunk<V> c = chunks.get(chunkKey(x >> 3, z >> 3));
        return c == null ? null : c.get(local(x, z, slot));
    }

    @Nullable
    V put(int x, int z, int slot, V value) {
        long key = chunkKey(x >> 3, z >> 3);
        Chunk<V> c = chunks.get(key);
        int local = local(x, z, slot);
        V prev = c == null ? null : c.get(local);
        chunks.put(key, c == null ? Chunk.of(local, value) : c.with(local, value));
        if (prev == null) {
            size++;
        }
        frozen = null;
        return prev;
    }

    @Nullable
    V remove(int x, int z, int slot) {
        long key = chunkKey(x >> 3, z >> 3);
        Chunk<V> c = chunks.get(key);
        if (c == null) {
            return null;
        }
        int local = local(x, z, slot);
        V prev = c.get(local);
        if (prev == null) {
            return null;
        }
        Chunk<V> next = c.without(local);
        if (next == null) {
            chunks.remove(key);
        } else {
            chunks.put(key, next);
        }
        size--;
        frozen = null;
        return prev;
    }

    Collection<V> clearChunk(int cx, int cz) {
        Chunk<V> c = chunks.remove(chunkKey(cx, cz));
        if (c == null) {
            return java.util.List.of();
        }
        size -= c.size();
        frozen = null;
        return c.values();
    }

    Frozen<V> freeze() {
        Frozen<V> f = frozen;
        if (f == null) {
            f = new Frozen<>(slots, new Long2ObjectOpenHashMap<>(chunks), size);
            frozen = f;
        }
        return f;
    }

    static final class Chunk<V> {
        private final short[] keys;
        private final Object[] vals;

        private Chunk(short[] keys, Object[] vals) {
            this.keys = keys;
            this.vals = vals;
        }

        static <V> Chunk<V> of(int local, V value) {
            return new Chunk<>(new short[] {(short) local}, new Object[] {value});
        }

        int size() {
            return keys.length;
        }

        @SuppressWarnings("unchecked")
        @Nullable
        V get(int local) {
            int i = Arrays.binarySearch(keys, (short) local);
            return i < 0 ? null : (V) vals[i];
        }

        @SuppressWarnings("unchecked")
        V valueAt(int i) {
            return (V) vals[i];
        }

        Chunk<V> with(int local, V value) {
            int i = Arrays.binarySearch(keys, (short) local);
            if (i >= 0) {
                Object[] v = vals.clone();
                v[i] = value;
                return new Chunk<>(keys, v);
            }
            int at = -i - 1;
            short[] k = new short[keys.length + 1];
            Object[] v = new Object[vals.length + 1];
            System.arraycopy(keys, 0, k, 0, at);
            System.arraycopy(vals, 0, v, 0, at);
            k[at] = (short) local;
            v[at] = value;
            System.arraycopy(keys, at, k, at + 1, keys.length - at);
            System.arraycopy(vals, at, v, at + 1, vals.length - at);
            return new Chunk<>(k, v);
        }

        @Nullable
        Chunk<V> without(int local) {
            if (keys.length == 1) {
                return null;
            }
            int at = Arrays.binarySearch(keys, (short) local);
            short[] k = new short[keys.length - 1];
            Object[] v = new Object[vals.length - 1];
            System.arraycopy(keys, 0, k, 0, at);
            System.arraycopy(vals, 0, v, 0, at);
            System.arraycopy(keys, at + 1, k, at, keys.length - at - 1);
            System.arraycopy(vals, at + 1, v, at, vals.length - at - 1);
            return new Chunk<>(k, v);
        }

        @SuppressWarnings("unchecked")
        Collection<V> values() {
            return (Collection<V>) java.util.List.of(vals);
        }
    }

    static final class Frozen<V> {

        private final int slots;
        private final Long2ObjectMap<Chunk<V>> chunks;
        private final int size;
        private @Nullable Collection<V> all;

        private Frozen(int slots, Long2ObjectMap<Chunk<V>> chunks, int size) {
            this.slots = slots;
            this.chunks = chunks;
            this.size = size;
        }

        @Nullable
        V get(int x, int z, int slot) {
            Chunk<V> c = chunks.get(chunkKey(x >> 3, z >> 3));
            return c == null ? null : c.get(((((z & 7) << 3) | (x & 7)) * slots) + slot);
        }

        int size() {
            return size;
        }

        Collection<V> values() {
            Collection<V> view = all;
            if (view == null) {
                view = new AbstractCollection<>() {
                    @Override
                    public Iterator<V> iterator() {
                        return new Iterator<>() {
                            private final Iterator<Chunk<V>> outer =
                                    chunks.values().iterator();
                            private @Nullable Chunk<V> chunk;
                            private int i;

                            @Override
                            public boolean hasNext() {
                                while (chunk == null || i >= chunk.size()) {
                                    if (!outer.hasNext()) {
                                        return false;
                                    }
                                    chunk = outer.next();
                                    i = 0;
                                }
                                return true;
                            }

                            @Override
                            public V next() {
                                if (!hasNext()) {
                                    throw new NoSuchElementException();
                                }
                                return chunk.valueAt(i++);
                            }
                        };
                    }

                    @Override
                    public int size() {
                        return size;
                    }
                };
                all = view;
            }
            return view;
        }
    }
}
