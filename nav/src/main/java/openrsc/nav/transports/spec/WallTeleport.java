package openrsc.nav.transports.spec;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.WallObjectTransport;

/**
 * Every placement of a wall the server moves the player across rather than opening: from either flank
 * of a straight wall to the other, and through a diagonal wall's tile between opposite neighbours.
 * Each arm lands by the tile it is fired from, and exists only where both its tiles can be stood on.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/plugins/Functions.java#L1632">Functions.doWallMovePlayer</a>
 */
public record WallTeleport(int boundaryId, Prereq gate, int douseItemId, boolean diagonals, String note)
        implements TransportSpec {

    public static final int FLAME_WALL_ID = 210;

    private static final int COST = 20;

    /**
     * The Legends' Quest flame walls, crossed while carrying the magical fire pass, or by using the
     * blessed bowl of pure water on a segment.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestWallObjects.java#L131">LegendsQuestWallObjects.onOpBound</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestWallObjects.java#L254">LegendsQuestWallObjects.onUseBound</a>
     */
    public static WallTeleport flameWalls() {
        return new WallTeleport(
                FLAME_WALL_ID,
                Prereq.fact(Fact.carriedAtLeast(ItemId.MAGICAL_FIRE_PASS.id(), 1)),
                ItemId.BLESSED_GOLDEN_BOWL_WITH_PURE_WATER.id(),
                true,
                null);
    }

    /**
     * The Underground Pass railings of {@code boundaryId}, behind {@code gate}. The handler moves the
     * player through a straight railing only.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/obstacles/UndergroundPassObstaclesMap2.java#L277">UndergroundPassObstaclesMap2.onOpBound</a>
     */
    public static WallTeleport railings(int boundaryId, Prereq gate) {
        return new WallTeleport(boundaryId, gate, -1, false, null);
    }

    @Override
    public boolean namedByPlacement() {
        return true;
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        List<Transport> out = new ArrayList<>();
        for (BoundaryLocs.Loc b : env.boundaryLocs()) {
            if (b.id() == boundaryId) {
                crossings(out, env.collisionMap(), id, b.pos().x(), b.pos().y(), b.direction());
            }
        }
        return out;
    }

    private void crossings(List<Transport> out, CollisionMap map, String id, int x, int z, int dir) {
        if (!diagonals && dir != 0 && dir != 1) {
            return;
        }
        for (Point[] arm : arms(x, z, dir)) {
            Point e = arm[0];
            Point d = arm[1];
            if (!walkable(map, e) || !walkable(map, d)) {
                continue;
            }
            out.add(new WallObjectTransport(boundaryId, x, z, List.of(e), d.x(), d.y(), gate, COST, note)
                    .withPlacement(id)
                    .landingByEntry());
            if (douseItemId >= 0) {
                Prereq douse = Prereq.fact(Fact.carriedAtLeast(douseItemId, 1));
                out.add(new WallObjectTransport(boundaryId, x, z, List.of(e), d.x(), d.y(), douse, COST, note)
                        .withPlacement(id)
                        .withUseItem(douseItemId)
                        .landingByEntry());
            }
        }
    }

    private static List<Point[]> arms(int x, int z, int dir) {
        return switch (dir) {
            case 0 ->
                List.of(
                        new Point[] {Point.of(x, z), Point.of(x, z - 1)},
                        new Point[] {Point.of(x, z - 1), Point.of(x, z)});
            case 1 ->
                List.of(
                        new Point[] {Point.of(x, z), Point.of(x - 1, z)},
                        new Point[] {Point.of(x - 1, z), Point.of(x, z)});
            case 2, 3 ->
                List.of(
                        new Point[] {Point.of(x, z - 1), Point.of(x, z + 1)},
                        new Point[] {Point.of(x, z + 1), Point.of(x, z - 1)},
                        new Point[] {Point.of(x - 1, z), Point.of(x + 1, z)},
                        new Point[] {Point.of(x + 1, z), Point.of(x - 1, z)});
            default -> List.of();
        };
    }

    private static boolean walkable(CollisionMap map, Point t) {
        return map.inBounds(t.x(), t.y()) && !map.fullyBlocked(t.x(), t.y());
    }
}
