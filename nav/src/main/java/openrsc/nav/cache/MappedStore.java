package openrsc.nav.cache;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.zip.CRC32C;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One {@link NavCache} kept in files under its own directory and read in place:
 *
 * <pre>
 *   stamp            what the entries were computed from; a different one wipes the directory
 *   lock             held while the store is open, so two engines never share the directory
 *   index.log        append-only records; a key's last one wins, and a removal is a record too
 *   chunk-NNNN.bin   fixed-size files, each mapped once, entries written back to back
 * </pre>
 *
 * <p>An index record is little-endian: magic, key length, key, chunk, offset, length, the entry's
 * CRC32C and then the record's own. Nothing is synced to disk. Instead, opening replays the index up
 * to the first record that is short or fails its check and truncates it there, and an entry's CRC is
 * checked on its first read; an entry that fails is rebuilt. A crash loses entries but never serves
 * a wrong one.
 *
 * <p>Nothing is evicted and no space is reclaimed. Once {@code diskBytes} of chunk space is used,
 * and for any entry larger than a chunk, values go to {@code overflow}, an in-memory store, for the
 * rest of the run. Stats cover both tiers. Closing unmaps every chunk: no value read from the store
 * may be used after.
 */
final class MappedStore<K, V> implements NavCache<K, V>, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MappedStore.class);

    /** The file layout's version, recorded in the stamp beside the codec's own. */
    static final int FORMAT = 1;

    private static final int MAGIC = 0x4E415649;
    private static final long TOMBSTONE = -1;
    private static final String INDEX = "index.log";
    static final String STAMP = "stamp";
    static final String LOCK = "lock";

    private static final class Slot<V> {
        final int chunk;
        final long offset;
        final long length;
        final int crc;
        volatile V view;

        Slot(int chunk, long offset, long length, int crc) {
            this.chunk = chunk;
            this.offset = offset;
            this.length = length;
            this.crc = crc;
        }
    }

    private final Path dir;
    private final NavCodec<K, V> codec;
    private final long chunkBytes;
    private final long diskBytes;
    private final NavCache<K, V> overflow;
    private final Arena arena = Arena.ofShared();
    private final FileChannel lockChannel;
    private final FileLock lock;
    private final FileChannel indexChannel;
    private final ConcurrentHashMap<K, Slot<V>> index = new ConcurrentHashMap<>();
    private final List<MemorySegment> chunks = new ArrayList<>();

    private int cursorChunk;
    private long cursorOffset;
    private boolean warnedFull;

    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder loads = new LongAdder();
    private final LongAdder loadNanos = new LongAdder();

    private MappedStore(
            Path dir,
            NavCodec<K, V> codec,
            long chunkBytes,
            long diskBytes,
            NavCache<K, V> overflow,
            FileChannel lockChannel,
            FileLock lock)
            throws IOException {
        this.dir = dir;
        this.codec = codec;
        this.chunkBytes = chunkBytes;
        this.diskBytes = diskBytes;
        this.overflow = overflow;
        this.lockChannel = lockChannel;
        this.lock = lock;
        this.indexChannel = FileChannel.open(
                dir.resolve(INDEX), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        replay();
    }

    /**
     * Opens the store under {@code dir}, creating it, or returns {@code null} when another opener
     * holds it, in this process or another. A stamp other than {@code codec}'s wipes it first.
     */
    static <K, V> MappedStore<K, V> open(
            Path dir, NavCodec<K, V> codec, long chunkBytes, long diskBytes, NavCache<K, V> overflow)
            throws IOException {
        Files.createDirectories(dir);
        FileChannel lockChannel =
                FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = lockChannel.tryLock();
        } catch (OverlappingFileLockException e) {
            lock = null;
        }
        if (lock == null) {
            lockChannel.close();
            return null;
        }
        try {
            byte[] stamp = stampOf(codec);
            Path stampFile = dir.resolve(STAMP);
            if (!Files.exists(stampFile) || !Arrays.equals(Files.readAllBytes(stampFile), stamp)) {
                wipe(dir);
                // Only after the wipe: a crash in between leaves no stamp, so the next open wipes again.
                Files.write(stampFile, stamp);
            }
            return new MappedStore<>(dir, codec, chunkBytes, diskBytes, overflow, lockChannel, lock);
        } catch (IOException | RuntimeException e) {
            lock.release();
            lockChannel.close();
            throw e;
        }
    }

    @Override
    public V getIfPresent(K key) {
        V found = present(key);
        if (found != null) {
            hits.increment();
        } else {
            misses.increment();
        }
        return found;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        V found = present(key);
        if (found != null) {
            hits.increment();
            return found;
        }
        misses.increment();
        @SuppressWarnings("unchecked")
        V[] made = (V[]) new Object[1];
        // computeIfAbsent is what runs the loader once for threads missing this key together.
        Slot<V> slot = index.computeIfAbsent(key, k -> {
            V spilled = overflow.getIfPresent(k);
            if (spilled != null) {
                made[0] = spilled;
                return null;
            }
            long t0 = System.nanoTime();
            V value = loader.apply(k);
            loadNanos.add(System.nanoTime() - t0);
            loads.increment();
            made[0] = value;
            return value == null ? null : write(k, value);
        });
        if (slot != null) {
            V view = view(key, slot);
            if (view != null) {
                return view;
            }
            // The entry failed its check and left the index: build it again.
            return get(key, loader);
        }
        if (made[0] != null) {
            overflow.put(key, made[0]);
        }
        return made[0];
    }

    @Override
    public void put(K key, V value) {
        Slot<V> slot = write(key, value);
        if (slot != null) {
            index.put(key, slot);
            overflow.invalidate(key);
        } else {
            index.remove(key);
            overflow.put(key, value);
        }
    }

    @Override
    public void invalidate(K key) {
        overflow.invalidate(key);
        if (index.remove(key) != null) {
            try {
                append(key, (int) TOMBSTONE, TOMBSTONE, TOMBSTONE, 0);
            } catch (IOException e) {
                LOG.warn("nav: could not record the removal of a key under {}", dir, e);
            }
        }
    }

    @Override
    public NavCacheStats stats() {
        NavCacheStats spilled = overflow.stats();
        long used;
        synchronized (this) {
            used = (long) cursorChunk * chunkBytes + cursorOffset;
        }
        return new NavCacheStats(
                index.size() + spilled.entries(),
                used + spilled.heldBytes(),
                hits.sum(),
                misses.sum(),
                loads.sum(),
                loadNanos.sum(),
                spilled.evictedBytes());
    }

    @Override
    public void close() throws IOException {
        synchronized (this) {
            chunks.clear();
        }
        arena.close();
        indexChannel.close();
        lock.release();
        lockChannel.close();
    }

    private V present(K key) {
        Slot<V> slot = index.get(key);
        if (slot != null) {
            V view = view(key, slot);
            if (view != null) {
                return view;
            }
        }
        return overflow.getIfPresent(key);
    }

    private V view(K key, Slot<V> slot) {
        V view = slot.view;
        if (view != null) {
            return view;
        }
        synchronized (slot) {
            if (slot.view == null) {
                MemorySegment slice = sliceOf(slot);
                if (slice == null || crc(slice) != slot.crc) {
                    LOG.warn("nav: an entry under {} failed its check and will be rebuilt", dir);
                    index.remove(key, slot);
                    return null;
                }
                slot.view = codec.readValue(slice);
            }
            return slot.view;
        }
    }

    private MemorySegment sliceOf(Slot<V> slot) {
        if (slot.chunk < 0 || slot.offset < 0 || slot.length < 0 || slot.offset + slot.length > chunkBytes) {
            return null;
        }
        MemorySegment chunk;
        synchronized (this) {
            if (slot.chunk >= chunks.size()) {
                return null;
            }
            chunk = chunks.get(slot.chunk);
        }
        return chunk.asSlice(slot.offset, slot.length);
    }

    private Slot<V> write(K key, V value) {
        long length = codec.valueBytes(value);
        try {
            MemorySegment chunk;
            int at;
            long offset;
            synchronized (this) {
                if (length > chunkBytes) {
                    return null;
                }
                at = cursorChunk;
                offset = cursorOffset;
                if (offset + length > chunkBytes) {
                    at++;
                    offset = 0;
                }
                long end = align(offset + length);
                if ((long) at * chunkBytes + end > diskBytes) {
                    if (!warnedFull) {
                        warnedFull = true;
                        LOG.warn("nav: {} has used its {}MB; new entries stay in memory", dir, diskBytes >> 20);
                    }
                    return null;
                }
                while (chunks.size() <= at) {
                    mapChunk(chunks.size());
                }
                cursorChunk = at;
                cursorOffset = end;
                chunk = chunks.get(at);
            }
            MemorySegment slice = chunk.asSlice(offset, length);
            codec.writeValue(value, slice);
            int crc = crc(slice);
            append(key, at, offset, length, crc);
            Slot<V> slot = new Slot<>(at, offset, length, crc);
            slot.view = codec.readValue(slice);
            return slot;
        } catch (IOException e) {
            LOG.warn("nav: could not write an entry under {}; it stays in memory", dir, e);
            return null;
        }
    }

    private void append(K key, int chunk, long offset, long length, int crc) throws IOException {
        byte[] keyBytes = new byte[codec.keyBytes(key)];
        codec.writeKey(key, MemorySegment.ofArray(keyBytes));
        ByteBuffer record =
                ByteBuffer.allocate(4 + 4 + keyBytes.length + 4 + 8 + 8 + 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        record.putInt(MAGIC)
                .putInt(keyBytes.length)
                .put(keyBytes)
                .putInt(chunk)
                .putLong(offset)
                .putLong(length)
                .putInt(crc);
        CRC32C check = new CRC32C();
        check.update(record.array(), 0, record.position());
        record.putInt((int) check.getValue()).flip();
        synchronized (indexChannel) {
            while (record.hasRemaining()) {
                indexChannel.write(record);
            }
        }
    }

    private void replay() throws IOException {
        long size = indexChannel.size();
        ByteBuffer all = ByteBuffer.allocate(Math.toIntExact(size)).order(ByteOrder.LITTLE_ENDIAN);
        while (all.hasRemaining() && indexChannel.read(all, all.position()) > 0) {
            // positional reads until the buffer is full
        }
        all.flip();
        int validEnd = 0;
        int highestChunk = -1;
        while (all.remaining() >= 8) {
            int start = all.position();
            int magic = all.getInt();
            int keyLength = all.getInt();
            if (magic != MAGIC || keyLength < 0 || all.remaining() < (long) keyLength + 28) {
                break;
            }
            byte[] keyBytes = new byte[keyLength];
            all.get(keyBytes);
            int chunk = all.getInt();
            long offset = all.getLong();
            long length = all.getLong();
            int crc = all.getInt();
            int recordCrc = all.getInt();
            CRC32C check = new CRC32C();
            check.update(all.array(), start, all.position() - 4 - start);
            if ((int) check.getValue() != recordCrc) {
                break;
            }
            validEnd = all.position();
            K key = codec.readKey(MemorySegment.ofArray(keyBytes));
            if (length == TOMBSTONE) {
                index.remove(key);
                continue;
            }
            index.put(key, new Slot<>(chunk, offset, length, crc));
            highestChunk = Math.max(highestChunk, chunk);
            long end = align(offset + length);
            if (chunk > cursorChunk || (chunk == cursorChunk && end > cursorOffset)) {
                cursorChunk = chunk;
                cursorOffset = end;
            }
        }
        if (validEnd < size) {
            LOG.warn("nav: {} had a torn index tail; {} bytes dropped", dir, size - validEnd);
            indexChannel.truncate(validEnd);
        }
        indexChannel.position(validEnd);
        synchronized (this) {
            for (int i = 0; i <= highestChunk; i++) {
                mapChunk(i);
            }
        }
    }

    private void mapChunk(int i) throws IOException {
        Path file = dir.resolve(String.format("chunk-%04d.bin", i));
        try (FileChannel channel =
                FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            // The mapping outlives the channel; only closing the arena unmaps it.
            chunks.add(channel.map(FileChannel.MapMode.READ_WRITE, 0, chunkBytes, arena));
        }
    }

    private static int crc(MemorySegment slice) {
        CRC32C check = new CRC32C();
        check.update(slice.asByteBuffer());
        return (int) check.getValue();
    }

    private static long align(long bytes) {
        return (bytes + 7) & ~7L;
    }

    private static byte[] stampOf(NavCodec<?, ?> codec) {
        byte[] header = ("NAVSTORE " + FORMAT + " " + ByteOrder.nativeOrder() + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = codec.stamp();
        byte[] stamp = Arrays.copyOf(header, header.length + body.length);
        System.arraycopy(body, 0, stamp, header.length, body.length);
        return stamp;
    }

    private static void wipe(Path dir) throws IOException {
        Files.deleteIfExists(dir.resolve(INDEX));
        Files.deleteIfExists(dir.resolve(STAMP));
        try (DirectoryStream<Path> chunkFiles = Files.newDirectoryStream(dir, "chunk-*.bin")) {
            for (Path chunk : chunkFiles) {
                Files.delete(chunk);
            }
        }
    }
}
