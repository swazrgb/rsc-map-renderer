package openrsc.gamedata.world;

import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.defs.DoorDefs;
import openrsc.gamedata.defs.ObjectDef;
import openrsc.gamedata.defs.ObjectDefs;
import openrsc.gamedata.defs.SceneryLocs;

/**
 * The collision a static loc adds to the map: each tile it stamps and the mask on it, as the
 * server's {@code World.registerGameObject} sets them. Scenery of def type 1 fully blocks its
 * footprint, type 2 walls one edge of each footprint tile and the matching edge across it, and other
 * types add nothing; a boundary collides only when its door type is 1. The one departure from the
 * server is a generic free door, which is left unstamped. Every tile emitted is inside the map.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/world/World.java#L505">World.registerGameObject</a>
 */
public final class LocStamps {

    /** Receives one contribution: tile {@code (x, y)} gains {@code mask}. */
    public interface TileMask {
        void accept(int x, int y, int mask);
    }

    public enum Result {
        /** Nothing stamped: a type that does not collide, an unknown def or dir, or off the map. */
        NOT_BLOCKING,
        /**
         * A generic free door ({@link ObjectDefs#isGenericFreeDoor}, {@link DoorDefs#isGenericFreeDoor}),
         * left unstamped.
         */
        FREE_DOOR,
        /** At least one contribution was emitted. */
        STAMPED
    }

    private LocStamps() {}

    /** Emits the scenery loc's contributions; emits nothing unless the result is {@link Result#STAMPED}. */
    public static Result enumerateScenery(SceneryLocs.Loc loc, ObjectDefs defs, TileMask out) {
        ObjectDef def = defs.get(loc.id());
        if (def == null) {
            return Result.NOT_BLOCKING;
        }
        int typ = def.typ();
        if (typ != 1 && typ != 2) {
            return Result.NOT_BLOCKING;
        }
        int dir = loc.direction();
        int dx = loc.pos().x();
        int dy = loc.pos().y();
        if (typ == 2) {
            if (defs.isGenericFreeDoor(loc.id())) {
                return Result.FREE_DOOR;
            }
        }
        int w = def.width();
        int h = def.height();
        if (dir != 0 && dir != 4) {
            int t = w;
            w = h;
            h = t;
        }
        boolean emitted = false;
        for (int x = dx; x < dx + w; x++) {
            for (int y = dy; y < dy + h; y++) {
                if (!inBounds(x, y)) {
                    continue;
                }
                if (typ == 1) {
                    out.accept(x, y, CollisionMap.FULL_BLOCK_C);
                    emitted = true;
                } else {
                    switch (dir) {
                        case 0 -> {
                            out.accept(x, y, CollisionMap.WALL_EAST);
                            if (inBounds(x - 1, y)) {
                                out.accept(x - 1, y, CollisionMap.WALL_WEST);
                            }
                            emitted = true;
                        }
                        case 2 -> {
                            out.accept(x, y, CollisionMap.WALL_SOUTH);
                            if (inBounds(x, y + 1)) {
                                out.accept(x, y + 1, CollisionMap.WALL_NORTH);
                            }
                            emitted = true;
                        }
                        case 4 -> {
                            out.accept(x, y, CollisionMap.WALL_WEST);
                            if (inBounds(x + 1, y)) {
                                out.accept(x + 1, y, CollisionMap.WALL_EAST);
                            }
                            emitted = true;
                        }
                        case 6 -> {
                            out.accept(x, y, CollisionMap.WALL_NORTH);
                            if (inBounds(x, y - 1)) {
                                out.accept(x, y - 1, CollisionMap.WALL_SOUTH);
                            }
                            emitted = true;
                        }
                        default -> {}
                    }
                }
            }
        }
        return emitted ? Result.STAMPED : Result.NOT_BLOCKING;
    }

    /** Emits the boundary loc's contributions; emits nothing unless the result is {@link Result#STAMPED}. */
    public static Result enumerateBoundary(BoundaryLocs.Loc b, DoorDefs doorDefs, TileMask out) {
        int id = b.id();
        if (doorDefs.doorType(id) != 1) {
            return Result.NOT_BLOCKING;
        }
        int x = b.pos().x();
        int y = b.pos().y();
        if (!inBounds(x, y)) {
            return Result.NOT_BLOCKING;
        }
        int dir = b.direction();
        if (doorDefs.isGenericFreeDoor(id)) {
            return Result.FREE_DOOR;
        }
        switch (dir) {
            case 0 -> {
                out.accept(x, y, CollisionMap.WALL_NORTH);
                if (inBounds(x, y - 1)) {
                    out.accept(x, y - 1, CollisionMap.WALL_SOUTH);
                }
            }
            case 1 -> {
                out.accept(x, y, CollisionMap.WALL_EAST);
                if (inBounds(x - 1, y)) {
                    out.accept(x - 1, y, CollisionMap.WALL_WEST);
                }
            }
            case 2 -> {
                out.accept(x, y, CollisionMap.FULL_BLOCK_A);
            }
            case 3 -> {
                out.accept(x, y, CollisionMap.FULL_BLOCK_B);
            }
            default -> {
                return Result.NOT_BLOCKING;
            }
        }
        return Result.STAMPED;
    }

    private static boolean inBounds(int x, int y) {
        return x >= 0 && x < CollisionMap.WIDTH && y >= 0 && y < CollisionMap.HEIGHT;
    }
}
