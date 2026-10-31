package openrsc.nav.cache;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import openrsc.gamedata.WorldProfile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappedNavCachesTest {

    private static final long MB = 1L << 20;
    private static final NavStore STORE = NavStore.CLUSTER_LEGS;

    @TempDir
    Path dir;

    @Test
    @DisplayName("a value written by one engine is read in place by the next")
    void aValueSurvivesAReopen() throws Exception {
        try (MappedNavCaches caches = open(16 * MB)) {
            NavCache<String, Ints> store = store(caches, "v1");
            assertInstanceOf(MappedStore.class, store, "the store is file-backed");
            assertArrayEquals(
                    new int[] {1, 2, 3}, store.get("a", k -> Ints.of(1, 2, 3)).toArray());
        }
        try (MappedNavCaches caches = open(16 * MB)) {
            NavCache<String, Ints> store = store(caches, "v1");
            Ints read = store.get("a", k -> fail("a stored value was rebuilt instead of read"));
            assertArrayEquals(new int[] {1, 2, 3}, read.toArray());
            assertTrue(read.seg().isMapped(), "read in place from the mapping, not copied");
            assertEquals(1, store.stats().entries());
        }
    }

    @Test
    @DisplayName("a different stamp starts from empty, and the old entries are gone for good")
    void aChangedStampStartsEmpty() throws Exception {
        try (MappedNavCaches caches = open(16 * MB)) {
            store(caches, "v1").get("a", k -> Ints.of(7));
        }
        try (MappedNavCaches caches = open(16 * MB)) {
            assertNull(store(caches, "v2").getIfPresent("a"), "computed from other inputs");
        }
        try (MappedNavCaches caches = open(16 * MB)) {
            assertNull(store(caches, "v1").getIfPresent("a"), "wiped, not merely hidden");
        }
    }

    @Test
    @DisplayName("a torn index tail loses only the entry it tore, and appends continue cleanly")
    void aTornIndexTailLosesOnlyTheTornEntry() throws Exception {
        try (MappedNavCaches caches = open(16 * MB)) {
            NavCache<String, Ints> store = store(caches, "v1");
            store.get("a", k -> Ints.of(1));
            store.get("b", k -> Ints.of(2));
        }
        Path index = storeDir().resolve("index.log");
        try (FileChannel channel = FileChannel.open(index, StandardOpenOption.WRITE)) {
            channel.truncate(channel.size() - 5);
        }
        try (MappedNavCaches caches = open(16 * MB)) {
            NavCache<String, Ints> store = store(caches, "v1");
            assertArrayEquals(new int[] {1}, store.getIfPresent("a").toArray());
            assertNull(store.getIfPresent("b"), "its record was torn");
            store.get("b", k -> Ints.of(22));
        }
        try (MappedNavCaches caches = open(16 * MB)) {
            NavCache<String, Ints> store = store(caches, "v1");
            assertArrayEquals(new int[] {1}, store.getIfPresent("a").toArray());
            assertArrayEquals(new int[] {22}, store.getIfPresent("b").toArray(), "appended after the cut");
        }
    }

    @Test
    @DisplayName("an entry whose bytes were damaged is rebuilt, never served")
    void aDamagedEntryIsRebuilt() throws Exception {
        try (MappedNavCaches caches = open(16 * MB)) {
            store(caches, "v1").get("a", k -> Ints.of(5, 6, 7));
        }
        try (FileChannel chunk = FileChannel.open(
                storeDir().resolve("chunk-0000.bin"), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(1);
            chunk.read(b, 0);
            b.put(0, (byte) (b.get(0) ^ 0xFF)).rewind();
            chunk.write(b, 0);
        }
        try (MappedNavCaches caches = open(16 * MB)) {
            AtomicInteger builds = new AtomicInteger();
            Ints got = store(caches, "v1").get("a", k -> {
                builds.incrementAndGet();
                return Ints.of(5, 6, 7);
            });
            assertEquals(1, builds.get(), "the damaged entry failed its check and was rebuilt");
            assertArrayEquals(new int[] {5, 6, 7}, got.toArray());
        }
    }

    @Test
    @DisplayName("past the disk budget new entries stay in memory, readable but not persisted")
    void pastTheDiskBudgetEntriesStayInMemory() throws Exception {
        // A 12-byte value is aligned up to 16 bytes, so this budget fits exactly one.
        try (MappedNavCaches caches = open(16)) {
            NavCache<String, Ints> store = store(caches, "v1");
            store.get("a", k -> Ints.of(1, 2, 3));
            store.get("b", k -> Ints.of(4, 5, 6));
            assertArrayEquals(new int[] {4, 5, 6}, store.getIfPresent("b").toArray(), "kept in memory");
            assertEquals(2, store.stats().entries());
        }
        try (MappedNavCaches caches = open(16)) {
            NavCache<String, Ints> store = store(caches, "v1");
            assertNotNull(store.getIfPresent("a"));
            assertNull(store.getIfPresent("b"), "the overflow did not survive the restart");
        }
    }

    @Test
    @DisplayName("a second engine on the same directory keeps its store in memory instead")
    void aHeldDirectoryFallsBackToMemory() throws Exception {
        try (MappedNavCaches first = open(16 * MB);
                MappedNavCaches second = open(16 * MB)) {
            assertInstanceOf(MappedStore.class, store(first, "v1"));
            NavCache<String, Ints> other = store(second, "v1");
            assertFalse(other instanceof MappedStore, "the directory is locked by the first");
            assertArrayEquals(new int[] {9}, other.get("z", k -> Ints.of(9)).toArray(), "still works");
        }
    }

    @Test
    @DisplayName("threads missing one key together build it once, and a null load is not kept")
    void concurrentMissesBuildOnceAndNullIsNotKept() throws Exception {
        try (MappedNavCaches caches = open(16 * MB)) {
            NavCache<String, Ints> store = store(caches, "v1");
            AtomicInteger builds = new AtomicInteger();
            CountDownLatch building = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            Function<String, Ints> slowBuild = k -> {
                builds.incrementAndGet();
                building.countDown();
                try {
                    finish.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Ints.of(42);
            };
            Ints[] answers = new Ints[2];
            Thread first = new Thread(() -> answers[0] = store.get("k", slowBuild));
            Thread second = new Thread(() -> answers[1] = store.get("k", slowBuild));
            first.start();
            assertTrue(building.await(10, TimeUnit.SECONDS));
            second.start();
            // Released before the second asker parks, the build would be done and there would be no race.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (second.getState() != Thread.State.BLOCKED && second.getState() != Thread.State.WAITING) {
                assertTrue(System.nanoTime() < deadline, "second asker never blocked behind the build");
                Thread.onSpinWait();
            }
            finish.countDown();
            first.join();
            second.join();
            assertEquals(1, builds.get());
            assertArrayEquals(new int[] {42}, answers[0].toArray());
            assertArrayEquals(new int[] {42}, answers[1].toArray());

            AtomicInteger nulls = new AtomicInteger();
            assertNull(store.get("nothing", k -> {
                nulls.incrementAndGet();
                return null;
            }));
            assertNull(store.get("nothing", k -> {
                nulls.incrementAndGet();
                return null;
            }));
            assertEquals(2, nulls.get(), "an unusable key is recomputed, not remembered as absent");
        }
    }

    @Test
    @DisplayName("each world keeps its stores in a directory of its own, and a switch wipes neither")
    void worldsKeepTheirOwnStores() throws Exception {
        WorldProfile other = WorldProfile.builder("rsccoleslaw").build();
        try (MappedNavCaches uranium = forWorld(WorldProfile.authentic());
                MappedNavCaches coleslaw = forWorld(other)) {
            store(uranium, "uranium-data").get("a", k -> Ints.of(1));
            store(coleslaw, "coleslaw-data").get("a", k -> Ints.of(2));
        }
        try (MappedNavCaches uranium = forWorld(WorldProfile.authentic());
                MappedNavCaches coleslaw = forWorld(other)) {
            assertArrayEquals(
                    new int[] {1},
                    store(uranium, "uranium-data")
                            .get("a", k -> fail("uranium's entry was lost to coleslaw's stamp"))
                            .toArray());
            assertArrayEquals(
                    new int[] {2},
                    store(coleslaw, "coleslaw-data")
                            .get("a", k -> fail("coleslaw's entry was lost to uranium's stamp"))
                            .toArray());
        }
        assertTrue(Files.isDirectory(dir.resolve("uranium").resolve("cluster_legs")));
        assertTrue(Files.isDirectory(dir.resolve("rsccoleslaw").resolve("cluster_legs")));
    }

    @Test
    @DisplayName("the authentic world adopts the stores the flat layout left under the root")
    void theFlatLayoutBecomesUranium() throws Exception {
        try (MappedNavCaches flat = open(16 * MB)) {
            store(flat, "v1").get("a", k -> Ints.of(5));
        }
        try (MappedNavCaches coleslaw =
                forWorld(WorldProfile.builder("rsccoleslaw").build())) {
            assertNull(store(coleslaw, "v1").getIfPresent("a"), "another world never adopts them");
        }
        assertTrue(Files.isDirectory(storeDir()), "left in place for the world they belong to");

        try (MappedNavCaches uranium = forWorld(WorldProfile.authentic())) {
            assertArrayEquals(
                    new int[] {5},
                    store(uranium, "v1")
                            .get("a", k -> fail("the flat store was not adopted"))
                            .toArray());
        }
        assertFalse(Files.exists(storeDir()), "moved, not copied");
    }

    @Test
    @DisplayName("a world that already has a store leaves the flat one alone")
    void aWorldsOwnStoreWinsOverTheFlatOne() throws Exception {
        try (MappedNavCaches uranium = forWorld(WorldProfile.authentic())) {
            store(uranium, "v1").get("a", k -> Ints.of(1));
        }
        try (MappedNavCaches flat = open(16 * MB)) {
            store(flat, "v1").get("a", k -> Ints.of(2));
        }
        try (MappedNavCaches uranium = forWorld(WorldProfile.authentic())) {
            assertArrayEquals(
                    new int[] {1},
                    store(uranium, "v1").get("a", k -> fail("rebuilt")).toArray());
        }
        assertTrue(Files.isDirectory(storeDir()), "the flat store is not moved over the world's own");
    }

    @Test
    @DisplayName("a flat store another engine holds stays put, in memory for this run, and moves later")
    void aHeldFlatStoreIsAdoptedLater() throws Exception {
        try (MappedNavCaches flat = open(16 * MB)) {
            store(flat, "v1").get("a", k -> Ints.of(3));
            try (MappedNavCaches uranium = forWorld(WorldProfile.authentic())) {
                NavCache<String, Ints> inMemory = store(uranium, "v1");
                assertFalse(inMemory instanceof MappedStore, "not a world copy beside the held one");
                assertFalse(Files.exists(dir.resolve("uranium").resolve("cluster_legs")), "nothing to orphan");
            }
            assertTrue(Files.isDirectory(storeDir()), "left where its holder has it");
        }
        try (MappedNavCaches uranium = forWorld(WorldProfile.authentic())) {
            assertArrayEquals(
                    new int[] {3},
                    store(uranium, "v1")
                            .get("a", k -> fail("the flat store was not adopted once free"))
                            .toArray());
        }
    }

    @Test
    @DisplayName("only a store is adopted: a directory without a stamp stays where it is")
    void aDirectoryWithoutAStampIsNotAStore() throws Exception {
        Files.createDirectories(storeDir());
        try (MappedNavCaches uranium = forWorld(WorldProfile.authentic())) {
            store(uranium, "v1").get("a", k -> Ints.of(1));
        }
        assertTrue(Files.isDirectory(storeDir()), "not moved");
    }

    private MappedNavCaches open(long diskBytes) {
        return new MappedNavCaches(dir, NavCacheBudget.DEFAULTS, diskBytes, MB);
    }

    private MappedNavCaches forWorld(WorldProfile world) {
        return MappedNavCaches.forWorld(dir, world, NavCacheBudget.DEFAULTS, 16 * MB, MB);
    }

    private static NavCache<String, Ints> store(MappedNavCaches caches, String stamp) {
        return caches.persisted(STORE, v -> v.seg().byteSize(), new IntsCodec(stamp));
    }

    private Path storeDir() {
        return dir.resolve("cluster_legs");
    }

    record Ints(MemorySegment seg) {
        static Ints of(int... values) {
            return new Ints(MemorySegment.ofArray(values.clone()));
        }

        int[] toArray() {
            return seg.toArray(ValueLayout.JAVA_INT_UNALIGNED);
        }
    }

    private record IntsCodec(String version) implements NavCodec<String, Ints> {

        @Override
        public byte[] stamp() {
            return version.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int keyBytes(String key) {
            return key.getBytes(StandardCharsets.UTF_8).length;
        }

        @Override
        public void writeKey(String key, MemorySegment into) {
            MemorySegment.copy(
                    MemorySegment.ofArray(key.getBytes(StandardCharsets.UTF_8)), 0, into, 0, into.byteSize());
        }

        @Override
        public String readKey(MemorySegment from) {
            return new String(from.toArray(ValueLayout.JAVA_BYTE), StandardCharsets.UTF_8);
        }

        @Override
        public long valueBytes(Ints value) {
            return value.seg().byteSize();
        }

        @Override
        public void writeValue(Ints value, MemorySegment into) {
            MemorySegment.copy(value.seg(), 0, into, 0, value.seg().byteSize());
        }

        @Override
        public Ints readValue(MemorySegment from) {
            return new Ints(from);
        }
    }
}
