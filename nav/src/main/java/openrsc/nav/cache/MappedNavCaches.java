package openrsc.nav.cache;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.ToLongFunction;
import openrsc.gamedata.WorldProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link CaffeineNavCaches}, except that every {@link #persisted} store lives in files under
 * {@code dir/<store>/} (the store's name in lower case), is read in place through a memory mapping,
 * and survives a restart. A persisted store never evicts; past its disk budget, new entries stay in
 * an in-memory store for the rest of the run.
 *
 * <p>A persisted store falls back to memory, with a warning, when another engine holds its
 * directory (in this process or another) or it cannot be opened: a misconfigured directory costs
 * warmth, never correctness.
 *
 * <p>Close it when the engine is done with it. Until then the files stay mapped and the directories
 * locked; after, no value read from a persisted store may be used.
 */
public final class MappedNavCaches implements NavCaches, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MappedNavCaches.class);

    static final long CHUNK_BYTES = 64L << 20;

    private final Path dir;
    private final long diskBytes;
    private final long chunkBytes;
    private final CaffeineNavCaches heap;
    private final List<MappedStore<?, ?>> opened = new CopyOnWriteArrayList<>();
    private final Set<NavStore> inMemory;

    /**
     * @param budget sizes the in-memory stores, and each persisted store's overflow
     * @param diskBytesPerStore chunk space, in bytes, one persisted store may write before new
     *     entries stay in memory; positive, or this throws {@link IllegalArgumentException}
     */
    public MappedNavCaches(Path dir, NavCacheBudget budget, long diskBytesPerStore) {
        this(dir, budget, diskBytesPerStore, CHUNK_BYTES);
    }

    /**
     * The stores of {@code world}, under {@code root/<world name>/}. A store's stamp covers its
     * world's data, so worlds sharing one directory would wipe each other's stores on every switch.
     *
     * <p>For the authentic world, each store found directly under {@code root} is first moved into
     * the world's directory, unless that already has the store. One that another engine holds is
     * left in place, and this engine keeps that store in memory for the run.
     */
    public static MappedNavCaches forWorld(
            Path root, WorldProfile world, NavCacheBudget budget, long diskBytesPerStore) {
        return forWorld(root, world, budget, diskBytesPerStore, CHUNK_BYTES);
    }

    static MappedNavCaches forWorld(
            Path root, WorldProfile world, NavCacheBudget budget, long diskBytesPerStore, long chunkBytes) {
        Path dir = root.resolve(world.name());
        Set<NavStore> heldFlat = world.name().equals(WorldProfile.authentic().name())
                ? adoptFlatLayout(root, dir)
                : EnumSet.noneOf(NavStore.class);
        return new MappedNavCaches(dir, budget, diskBytesPerStore, chunkBytes, heldFlat);
    }

    static Set<NavStore> adoptFlatLayout(Path root, Path worldDir) {
        Set<NavStore> held = EnumSet.noneOf(NavStore.class);
        for (NavStore store : NavStore.values()) {
            Path flat = root.resolve(dirName(store));
            Path target = worldDir.resolve(dirName(store));
            if (!Files.isRegularFile(flat.resolve(MappedStore.STAMP)) || Files.exists(target)) {
                continue;
            }
            try {
                if (heldByAnother(flat)) {
                    LOG.warn("nav: {} is held by another engine; {} stays in memory until it can move", flat, store);
                    held.add(store);
                    continue;
                }
                Files.createDirectories(worldDir);
                Files.move(flat, target, StandardCopyOption.ATOMIC_MOVE);
                LOG.info("nav: moved {} to {} (a cache directory per world)", flat, target);
            } catch (IOException | RuntimeException e) {
                LOG.warn("nav: cannot move {} to {}; {} starts cold there", flat, target, store, e);
            }
        }
        return held;
    }

    private static boolean heldByAnother(Path storeDir) throws IOException {
        Path lockFile = storeDir.resolve(MappedStore.LOCK);
        if (!Files.exists(lockFile)) {
            return false;
        }
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                return true;
            }
            if (lock == null) {
                return true;
            }
            lock.release();
            return false;
        }
    }

    MappedNavCaches(Path dir, NavCacheBudget budget, long diskBytesPerStore, long chunkBytes) {
        this(dir, budget, diskBytesPerStore, chunkBytes, EnumSet.noneOf(NavStore.class));
    }

    private MappedNavCaches(
            Path dir, NavCacheBudget budget, long diskBytesPerStore, long chunkBytes, Set<NavStore> inMemory) {
        this.dir = Objects.requireNonNull(dir, "dir");
        this.inMemory = inMemory;
        this.heap = new CaffeineNavCaches(budget);
        if (diskBytesPerStore <= 0) {
            throw new IllegalArgumentException("diskBytesPerStore must be positive; got " + diskBytesPerStore);
        }
        this.diskBytes = diskBytesPerStore;
        this.chunkBytes = chunkBytes;
    }

    @Override
    public <K, V> NavCache<K, V> weighed(NavStore store, ToLongFunction<? super V> bytes) {
        return heap.weighed(store, bytes);
    }

    @Override
    public <K, V> NavCache<K, V> persisted(NavStore store, ToLongFunction<? super V> bytes, NavCodec<K, V> codec) {
        NavCache<K, V> overflow = heap.weighed(store, bytes);
        if (inMemory.contains(store)) {
            return overflow;
        }
        Path storeDir = dir.resolve(dirName(store));
        try {
            MappedStore<K, V> mapped = MappedStore.open(storeDir, codec, chunkBytes, diskBytes, overflow);
            if (mapped == null) {
                LOG.warn("nav: {} is held by another engine; {} stays in memory", storeDir, store);
                return overflow;
            }
            opened.add(mapped);
            NavCacheStats s = mapped.stats();
            LOG.info(
                    "nav: {} kept under {} — {} entries, {}MB on disk",
                    store,
                    storeDir,
                    s.entries(),
                    s.heldBytes() >> 20);
            return mapped;
        } catch (IOException | RuntimeException e) {
            LOG.warn("nav: cannot keep {} under {}; it stays in memory", store, storeDir, e);
            return overflow;
        }
    }

    private static String dirName(NavStore store) {
        return store.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public void close() throws IOException {
        for (MappedStore<?, ?> store : opened) {
            store.close();
        }
        opened.clear();
    }
}
