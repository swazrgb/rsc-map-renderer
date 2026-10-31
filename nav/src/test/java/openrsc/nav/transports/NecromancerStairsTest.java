package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NecromancerStairsTest {

    private static final int STAIRS_UP_X = 558;

    private static final int STAIRS_UP_Y = 645;
    private static final int STAIRS_DOWN_X = 558;

    private static final int STAIRS_DOWN_Y = 1589;

    private static final int GROUND_NECROMANCER_Y = 649;
    private static final int UPSTAIRS_NECROMANCER_Y = 1592;

    private static List<Transport> transports;

    @BeforeAll
    static void build() throws Exception {
        ServerData env = RealDefs.serverData();

        List<Transport> all = new ArrayList<>(Climbs.all(env));
        all.addAll(LadderSpecials.all(env));
        transports = all;
    }

    @Test
    void theGroundFloorStaircaseLeadsUpToTheNecromancer() {
        Transport up = nearest(STAIRS_UP_X, STAIRS_UP_Y, 0);
        assertNotNull(up, "no transport with an entry beside the stairs at (558,645)");

        Point dest = destination(up);
        assertEquals(1, dest.y() / 944, up + " must land on floor 1, got y=" + dest.y());
        assertTrue(
                Math.abs(dest.y() - UPSTAIRS_NECROMANCER_Y) <= 12,
                up + " must land within reach of the upstairs necromancer @(558,1592), got y=" + dest.y());
    }

    @Test
    void theFirstFloorStaircaseLeadsBackDown() {
        Transport down = nearest(STAIRS_DOWN_X, STAIRS_DOWN_Y, 1);
        assertNotNull(down, "no transport with an entry beside the stairs at (558,1589)");

        Point dest = destination(down);
        assertEquals(0, dest.y() / 944, down + " must land on the ground floor, got y=" + dest.y());
        assertTrue(
                Math.abs(dest.y() - GROUND_NECROMANCER_Y) <= 12,
                down + " must land within reach of the ground necromancer @(559,649), got y=" + dest.y());
    }

    @Test
    void theFloorsAreConnectedInBothDirections() {
        assertNotNull(nearest(STAIRS_UP_X, STAIRS_UP_Y, 0), "up");
        assertNotNull(nearest(STAIRS_DOWN_X, STAIRS_DOWN_Y, 1), "down");
    }

    private static Transport nearest(int x, int y, int floor) {
        for (Transport t : transports) {
            for (Point e : t.entries()) {
                if (e.y() / 944 != floor) {
                    continue;
                }
                if (Math.abs(e.x() - x) <= 1 && Math.abs(e.y() - y) <= 1) {
                    return t;
                }
            }
        }
        return null;
    }

    private static Point destination(Transport t) {
        Point entry = t.entries().get(0);
        Point dest = t.destinationFrom(entry);
        assertNotNull(dest, t + " produced no destination from its own entry");
        return dest;
    }
}
