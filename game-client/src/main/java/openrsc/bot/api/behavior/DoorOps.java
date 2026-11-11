package openrsc.bot.api.behavior;

import java.util.List;
import openrsc.bot.api.Action;
import openrsc.bot.api.GameObject;
import openrsc.bot.api.GameState;
import openrsc.bot.api.WallObject;
import openrsc.bot.api.packets.AtObjectPacket;
import openrsc.bot.api.packets.AtWallObjectPacket;
import openrsc.gamedata.geometry.Point;
import org.jetbrains.annotations.Nullable;

/**
 * Opening the closed doors and gates a walk meets that any player may open. The world's collision map reads them open,
 * so a path runs through them; a walk clicks each one it finds standing closed in its way.
 */
public final class DoorOps {

    /** How many of a walk's steps ahead are looked at for a closed door. */
    private static final int LOOKAHEAD_EDGES = 4;

    /** How far from the player, in tiles, a door may be clicked. */
    private static final int CLICK_RANGE = 2;

    private DoorOps() {}

    /**
     * The click on the first closed door among the next steps of {@code walk}, its tiles from the one stood on.
     *
     * @return null when none is closed
     */
    public static @Nullable Ahead ahead(GameState state, Point pos, List<Point> walk) {
        int end = Math.min(walk.size() - 1, LOOKAHEAD_EDGES);
        for (int i = 0; i < end; i++) {
            Point a = walk.get(i);
            Point b = walk.get(i + 1);
            if (pos.chebyshev(a) > CLICK_RANGE) {
                continue;
            }
            Action click = open(state, a.x(), a.y(), b.x(), b.y());
            if (click != null) {
                return new Ahead(click, i);
            }
        }
        return null;
    }

    /**
     * A closed door ahead.
     *
     * @param step which step of the walk crosses it: 0 for the one taken next
     */
    public record Ahead(Action click, int step) {}

    /**
     * The click that opens a closed door or gate on the step from ({@code fx}, {@code fy}) to the adjacent tile
     * ({@code tx}, {@code ty}), a diagonal step's two edges included; null when none is closed.
     */
    public static @Nullable Action open(GameState state, int fx, int fy, int tx, int ty) {
        int dx = tx - fx;
        int dy = ty - fy;
        if ((dx == 0 && dy == 0) || Math.abs(dx) > 1 || Math.abs(dy) > 1) {
            return null;
        }
        Action click;
        if (dx == 0 || dy == 0) {
            click = onEdge(state, fx, fy, tx, ty);
        } else {
            click = onEdge(state, fx, fy, fx + dx, fy);
            if (click == null) {
                click = onEdge(state, fx, fy, fx, fy + dy);
            }
            if (click == null) {
                click = onEdge(state, fx, fy + dy, tx, ty);
            }
            if (click == null) {
                click = onEdge(state, fx + dx, fy, tx, ty);
            }
        }
        if (click == null) {
            click = diagonalDoorOn(state, tx, ty);
        }
        if (click == null && dx != 0 && dy != 0) {
            click = diagonalDoorOn(state, fx + dx, fy);
            if (click == null) {
                click = diagonalDoorOn(state, fx, fy + dy);
            }
        }
        return click;
    }

    private static @Nullable Action onEdge(GameState state, int ax, int ay, int bx, int by) {
        int dx = bx - ax;
        int dy = by - ay;
        int wx = dx == 1 ? ax + 1 : ax;
        int wz = dy == 1 ? ay + 1 : ay;
        int wdir = dy != 0 ? 0 : 1;
        WallObject wall = state.walls().at(wx, wz, wdir);
        if (wall != null && state.serverData().isGenericFreeDoor(wall.id())) {
            return clickWall(wall, ax, ay, bx, by);
        }
        GameObject gate = freeGateOnEdge(state, wx, wz, wdir);
        return gate == null ? null : clickGate(gate, ax, ay, bx, by);
    }

    private static @Nullable Action diagonalDoorOn(GameState state, int x, int z) {
        for (int dir = 2; dir <= 3; dir++) {
            WallObject wall = state.walls().at(x, z, dir);
            if (wall != null && state.serverData().isGenericFreeDoor(wall.id())) {
                return clickWall(wall, x, z, x, z);
            }
        }
        GameObject gate = freeGate(state, state.scenery().covering(x, z));
        return gate != null && (gate.dir() & 1) == 1 ? clickGate(gate, x, z, x, z) : null;
    }

    private static Action clickWall(WallObject wall, int fx, int fy, int tx, int ty) {
        return Action.of(
                c -> c.describe(describe("wall", wall.id(), wall.x(), wall.z(), fx, fy, tx, ty)),
                new AtWallObjectPacket(wall.x(), wall.z(), wall.dir()));
    }

    private static Action clickGate(GameObject gate, int fx, int fy, int tx, int ty) {
        return Action.of(
                c -> c.describe(describe("scenery", gate.id(), gate.x(), gate.z(), fx, fy, tx, ty)),
                new AtObjectPacket(gate.x(), gate.z()));
    }

    private static @Nullable GameObject freeGateOnEdge(GameState state, int wx, int wz, int wdir) {
        if (wdir == 1) {
            GameObject east = freeGate(state, state.scenery().covering(wx, wz));
            if (east != null && east.dir() == 0) {
                return east;
            }
            GameObject west = freeGate(state, state.scenery().covering(wx - 1, wz));
            return west != null && west.dir() == 4 ? west : null;
        }
        GameObject north = freeGate(state, state.scenery().covering(wx, wz));
        if (north != null && north.dir() == 6) {
            return north;
        }
        GameObject south = freeGate(state, state.scenery().covering(wx, wz - 1));
        return south != null && south.dir() == 2 ? south : null;
    }

    private static @Nullable GameObject freeGate(GameState state, @Nullable GameObject obj) {
        return obj != null && state.serverData().isGenericFreeSceneryDoor(obj.id()) ? obj : null;
    }

    private static String describe(String kind, int id, int ox, int oy, int fx, int fy, int tx, int ty) {
        return "open " + kind + " door " + id + " @(" + ox + "," + oy + ") blocking (" + fx + "," + fy + ")->(" + tx
                + "," + ty + ")";
    }
}
