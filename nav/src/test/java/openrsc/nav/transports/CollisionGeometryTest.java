package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CollisionGeometryTest {

    private static CollisionMap map;

    @BeforeAll
    static void loadMap() throws Exception {
        map = RealDefs.collisionMap();
    }

    @Test
    void enumerateEntriesRectMatchesEngageTilesAround() {
        int checked = 0;
        for (int y = 600; y < 650; y += 3) {
            for (int x = 110; x < 160; x += 3) {
                for (int w = 1; w <= 2; w++) {
                    for (int h = 1; h <= 2; h++) {
                        Rect rect = Rect.of(x, y, x + w - 1, y + h - 1);
                        List<Point> expected = map.engageTilesAround(rect, Overlays.NONE);
                        List<Point> actual = Transport.enumerateEntriesRect(map, x, y, w, h);
                        assertEquals(expected, actual, () -> "entry mismatch for " + rect);
                        checked++;
                    }
                }
            }
        }
        assertTrue(checked > 100, "expected a meaningful sweep, only checked " + checked);
    }

    @Test
    void enumerateEntriesIsTheOneByOneRect() {
        for (int y = 600; y < 650; y += 5) {
            for (int x = 110; x < 160; x += 5) {
                final int ax = x;
                final int ay = y;
                // As sets: the single-anchor form lists the anchor first.
                assertEquals(
                        java.util.Set.copyOf(Transport.enumerateEntriesRect(map, ax, ay, 1, 1)),
                        java.util.Set.copyOf(Transport.enumerateEntries(map, ax, ay)),
                        () -> "1x1 entries differ at (" + ax + "," + ay + ")");
            }
        }
    }
}
