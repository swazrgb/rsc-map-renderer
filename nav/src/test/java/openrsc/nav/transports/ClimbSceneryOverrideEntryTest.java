package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ClimbSceneryOverrideEntryTest {

    private static CollisionMap map;
    private static List<Transport> generated;
    private static List<Transport> specials;

    @BeforeAll
    static void build() throws Exception {
        ServerData env = RealDefs.serverData();

        map = env.collisionMap();

        generated = Climbs.all(env);
        specials = LadderSpecials.all(env);
    }

    /**
     * The Mage Arena ladder stands on a blocked tile and has a tele point, which lands on a fixed tile
     * whichever side it is climbed from.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L113">Ladders.onObjectAction</a>
     */
    @Test
    void mageArenaDownLadderHasWalkableNeighbourEntries() {
        assertTrue(map.fullyBlocked(223, 110), "precondition: the ladder's own tile (223,110) is blocking scenery");

        Transport ladder = generated.stream()
                .filter(t -> t.isEntry(223, 109) || t.isEntry(224, 110) || t.isEntry(222, 110) || t.isEntry(223, 111))
                .filter(t -> destOf(t, 223, 110).x() == 446)
                .findFirst()
                .orElse(null);
        assertTrue(ladder != null, "expected an override ladder transport with engageable neighbours of (223,110)");

        List<Point> entries = ladder.entries();
        assertFalse(entries.isEmpty(), "ladder must have at least one entry tile");
        for (Point e : entries) {
            assertFalse(e.x() == 223 && e.y() == 110, "no entry may sit on the blocked object tile (223,110)");
            assertFalse(map.fullyBlocked(e.x(), e.y()), "entry (" + e.x() + "," + e.y() + ") must be walkable");
            assertTrue(
                    map.canReachScenery(e.x(), e.y(), 223, 223, 110, 110, null),
                    "entry (" + e.x() + "," + e.y() + ") must be a tile the server takes the click from");
        }
    }

    @Test
    void everyGeneratedLadderHasAtLeastOneStandableEntry() {
        StringBuilder bad = new StringBuilder();
        for (Transport t : generated) {
            boolean anyStandable = false;
            for (Point e : t.entries()) {
                if (map.inBounds(e.x(), e.y()) && !map.fullyBlocked(e.x(), e.y())) {
                    anyStandable = true;
                    break;
                }
            }
            if (!anyStandable) {
                bad.append("\n  ")
                        .append(t.name())
                        .append(" entries=")
                        .append(t.entries().size());
            }
        }
        assertTrue(bad.length() == 0, "generated ladders with no standable entry tile:" + bad);
    }

    @Test
    void everySpecialLadderHasAtLeastOneStandableEntry() {
        StringBuilder bad = new StringBuilder();
        for (Transport t : specials) {
            boolean anyStandable = false;
            for (Point e : t.entries()) {
                if (map.inBounds(e.x(), e.y()) && !map.fullyBlocked(e.x(), e.y())) {
                    anyStandable = true;
                    break;
                }
            }
            if (!anyStandable) {
                bad.append("\n  ")
                        .append(t.name())
                        .append(" entries=")
                        .append(t.entries().size());
            }
        }
        assertTrue(bad.length() == 0, "special ladders with no standable entry tile:" + bad);
    }

    /**
     * The tele point of (446,3367) is read before the server's own branch for 1187, which would land on
     * (222,110) and so never runs.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L113">Ladders.onObjectAction</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L261">Ladders.onObjectAction</a>
     */
    @Test
    void brimstailExitMatchesServerOverrideNotDeadCode() {
        for (Transport t : specials) {
            assertFalse(
                    t.isEntry(446, 3367) || t.isEntry(445, 3367) || t.isEntry(447, 3367),
                    "specials must not hand-code the Brimstail override tile: " + t.name());
        }
        Transport ladder = generated.stream()
                .filter(t ->
                        t.isEntry(445, 3367) || t.isEntry(447, 3367) || t.isEntry(446, 3366) || t.isEntry(446, 3368))
                .filter(t ->
                        destOf(t, 446, 3367).x() == 223 && destOf(t, 446, 3367).y() == 109)
                .findFirst()
                .orElse(null);
        assertTrue(ladder != null, "expected ClimbScenery override for (446,3367)→(223,109) with neighbour entries");
        for (Point e : ladder.entries()) {
            assertFalse(
                    map.fullyBlocked(e.x(), e.y()), "Brimstail entry (" + e.x() + "," + e.y() + ") must be walkable");
        }
    }

    @Test
    void hardcodedStairsUseServerDestinationNotFormula() {
        int[][] cases = {
            {576, 3529, 576, 3580
            }, // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L54
            {605, 3509, 606, 3556
            }, // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L58
        };
        for (int[] c : cases) {
            int ox = c[0], oy = c[1], sx = c[2], sy = c[3];
            Transport stair = specials.stream()
                    .filter(t -> ((ScriptedLadderTransport) t).objectX() == ox
                            && ((ScriptedLadderTransport) t).objectY() == oy)
                    .findFirst()
                    .orElse(null);
            assertTrue(stair != null, "expected special for stairs @(" + ox + "," + oy + ")");
            Point dest = stair.destinationFrom(Point.of(ox, oy));
            assertTrue(
                    dest.x() == sx && dest.y() == sy,
                    "stairs @(" + ox + "," + oy + ") must teleport to server dest (" + sx + "," + sy + "), got ("
                            + dest.x() + "," + dest.y() + ")");
        }
    }

    private static Point destOf(Transport t, int fx, int fy) {
        Point d = t.destinationFrom(Point.of(fx, fy));
        return d == null ? Point.of(-1, -1) : d;
    }
}
