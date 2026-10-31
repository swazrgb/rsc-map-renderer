package openrsc.nav.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NavCachesTest {

    private static final NavCaches CAFFEINE = new CaffeineNavCaches(NavCacheBudget.DEFAULTS);

    @Test
    @DisplayName("a null load is handed back and never kept, so it recomputes on the next ask")
    void aNullLoadIsNotKept() {
        NavCache<String, String> c = CAFFEINE.weighed(NavStore.GOAL_LABELS, String::length);
        AtomicInteger runs = new AtomicInteger();
        Function<String, String> unusable = k -> {
            runs.incrementAndGet();
            return null;
        };
        assertNull(c.get("tile", unusable));
        assertNull(c.getIfPresent("tile"));
        assertNull(c.get("tile", unusable));
        assertEquals(2, runs.get(), "recomputed, not remembered as absent");
        assertEquals(2, c.stats().loads(), "a null load still counts as a load");
        assertEquals(0, c.stats().entries());
    }

    @Test
    @DisplayName("threads missing one key together run its loader once and share the result")
    void concurrentMissesLoadOnce() throws Exception {
        NavCache<String, String> c = CAFFEINE.weighed(NavStore.METRICS, String::length);
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Function<String, String> slowBuild = k -> {
            runs.incrementAndGet();
            building.countDown();
            try {
                finish.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "surface";
        };
        String[] answers = new String[2];
        Thread first = new Thread(() -> answers[0] = c.get("goal", slowBuild));
        Thread second = new Thread(() -> answers[1] = c.get("goal", slowBuild));
        first.start();
        assertTrue(building.await(10, TimeUnit.SECONDS), "first build never started");
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

        assertEquals(1, runs.get(), "one build for two askers");
        assertEquals("surface", answers[0]);
        assertEquals("surface", answers[1]);
    }

    @Test
    @DisplayName("stats count hits, misses and loads the way the engine's stats line reads them")
    void statsCountAsks() {
        NavCache<String, String> c = CAFFEINE.weighed(NavStore.CLUSTER_LEGS, String::length);
        c.get("a", k -> "planes");
        c.get("a", k -> "planes");
        c.getIfPresent("b");

        NavCacheStats s = c.stats();
        assertEquals(1, s.entries());
        assertEquals(1, s.hits());
        assertEquals(2, s.misses());
        assertEquals(1, s.loads());
        assertEquals(1.0 / 3, s.hitRate(), 1e-9);
    }

    @Test
    @DisplayName("none() keeps nothing: a put is dropped and every ask computes")
    void noneKeepsNothing() {
        NavCache<String, String> c = NavCaches.none().weighed(NavStore.METRICS, String::length);
        AtomicInteger runs = new AtomicInteger();
        Function<String, String> build = k -> {
            runs.incrementAndGet();
            return "surface";
        };
        c.put("goal", "surface");
        assertNull(c.getIfPresent("goal"));
        assertEquals("surface", c.get("goal", build));
        assertEquals("surface", c.get("goal", build));
        assertEquals(2, runs.get());
    }
}
