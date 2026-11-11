package openrsc.nav.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NavCachesTest {

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
