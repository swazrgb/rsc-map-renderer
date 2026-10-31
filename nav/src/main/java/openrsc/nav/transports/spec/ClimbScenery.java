package openrsc.nav.transports.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.ObjectTelePoints;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.LadderSpecials;
import openrsc.nav.transports.LadderTransport;
import openrsc.nav.transports.ScriptedLadderTransport;

/**
 * Every placed scenery whose first command climbs up or down, as the server's default ladder handler
 * takes it: by the command alone. The landing is the object's tele point when the world defines one;
 * else, for a one-tile object, the player's own column one floor up or down; else a fixed tile beside
 * a multi-tile staircase, by its direction. Objects another handler scripts are left out: the ids in
 * {@link #SCRIPTED_IDS} and the placements {@link openrsc.nav.transports.LadderSpecials} declares.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L25">Ladders.blockObjectAction</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L111">Ladders.onObjectAction</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L286">Ladders.coordModifier</a>
 */
public record ClimbScenery() implements TransportSpec {

    /** A climb's cost, in the planner's units of 10 per walked tile. */
    public static final int COST = 30;

    /**
     * Climbing scenery that another handler catches by its id, so the default landing would be wrong;
     * each is declared where it is scripted, or not at all.
     */
    public static final Set<Integer> SCRIPTED_IDS = Set.of(
            130, 227, 331, 359, 369, 370, 458, 585, 586, 635, 646, 659, 920, 928, 981, 982, 983, 985, 997, 1017, 1021,
            1097, 1098, 244, 649, 648, 776, 249, 732, 750, 751, 752, 753, 816, 836, 838, 862, 863, 864, 865, 866, 872,
            709, 677, 629, 621, 199);

    public static ClimbScenery everyClimb() {
        return new ClimbScenery();
    }

    @Override
    public boolean namedByPlacement() {
        return true;
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        Set<ScriptedClimb.Placement> scripted = LadderSpecials.scriptedPlacements();
        List<Transport> out = new ArrayList<>();
        for (SceneryLocs.Loc loc : env.sceneryLocs()) {
            if (SCRIPTED_IDS.contains(loc.id())
                    || scripted.contains(new ScriptedClimb.Placement(
                            loc.id(), loc.pos().x(), loc.pos().y()))) {
                continue;
            }
            ObjectDef def = env.objectDefs().get(loc.id());
            Boolean up = climbsUp(def);
            if (up != null) {
                out.addAll(climb(loc, def, up, env, id));
            }
        }
        return out;
    }

    private static List<Transport> climb(SceneryLocs.Loc loc, ObjectDef def, boolean up, ServerData env, String id) {
        CollisionMap map = env.collisionMap();
        int objectId = loc.id();
        int x = loc.pos().x();
        int z = loc.pos().y();
        Point override = telePoint(env.objectTelePoints(), x, z);
        if (override != null) {
            List<Point> entries = Transport.entriesFor(map, x, z, def.width(), def.height(), loc.direction());
            if (entries.isEmpty()) {
                return List.of();
            }
            return List.of(new ScriptedLadderTransport(
                            objectId, x, z, override.x(), override.y(), Prereq.ALWAYS, COST, entries)
                    .withPlacement(id));
        }
        if (def.height() <= 1) {
            // Up from floor 2, or down from the basement, the server keeps the player's y:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/util/rsc/Formulae.java#L510
            if (LadderTransport.newY(z, up) == z) {
                return List.of();
            }
            List<Point> entries = Transport.entriesFor(map, x, z, def.width(), def.height(), loc.direction());
            if (entries.isEmpty()) {
                return List.of();
            }
            return List.of(new LadderTransport(objectId, x, z, up, entries, Prereq.ALWAYS, COST).withPlacement(id));
        }
        Point dst = staircaseLanding(x, z, loc.direction(), def.height(), up);
        if (dst == null) {
            return List.of();
        }
        List<Point> entries = Transport.entriesFor(map, x, z, def.width(), def.height(), loc.direction());
        if (entries.isEmpty()) {
            return List.of();
        }
        return List.of(new ScriptedLadderTransport(objectId, x, z, dst.x(), dst.y(), Prereq.ALWAYS, COST, entries)
                .withPlacement(id));
    }

    private static Point telePoint(List<ObjectTelePoints.Point> points, int x, int z) {
        for (ObjectTelePoints.Point p : points) {
            if (p.x() == x && p.y() == z) {
                return Point.of(p.destX(), p.destY());
            }
        }
        return null;
    }

    private static Point staircaseLanding(int x, int z, int dir, int height, boolean up) {
        int newY = LadderTransport.newY(z, up);
        if (newY == z) {
            return null;
        }
        int rx = x, ry = newY;
        switch (dir) {
            case 0 -> ry -= (up ? -height : 1);
            case 2 -> rx -= (up ? -height : 1);
            case 4 -> ry += (up ? -1 : height);
            case 6 -> rx += (up ? -1 : height);
            default -> {}
        }
        return Point.of(rx, ry);
    }

    static Boolean climbsUp(ObjectDef def) {
        String cmd = def == null ? null : def.command1();
        if (cmd == null) {
            return null;
        }
        String n = cmd.toLowerCase(Locale.ROOT).trim();
        if (n.equals("climb-up") || n.equals("climb up") || n.equals("go up")) {
            return true;
        }
        if (n.equals("climb-down") || n.equals("climb down") || n.equals("go down")) {
            return false;
        }
        return null;
    }
}
