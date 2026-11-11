package openrsc.bot.core.world;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Collection;
import openrsc.bot.api.GameObject;
import openrsc.bot.api.GroundItem;
import openrsc.bot.api.WallObject;
import openrsc.gamedata.world.StaticLocIndex;

public final class TailDue {

    public static final int REMOVE_OFFSET_LIMIT = 128;

    public static final int NPC_APPEARANCES = 104;
    public static final int SCENERY = 48;
    public static final int WALLS = 91;
    public static final int GROUND_ITEMS = 99;
    public static final int CLEAR = 211;

    @FunctionalInterface
    public interface Entering {
        boolean test(int cx, int cz);
    }

    private TailDue() {}

    public static int wireRank(int opcode) {
        return switch (opcode) {
            case NPC_APPEARANCES -> 0;
            case SCENERY -> 1;
            case WALLS -> 2;
            case GROUND_ITEMS -> 3;
            case CLEAR -> 4;
            default -> -1;
        };
    }

    public static int terminator(
            int px,
            int pz,
            int gridRadius,
            Entering entering,
            Collection<GameObject> scenery,
            Collection<WallObject> walls,
            Iterable<GroundItem> items,
            StaticLocIndex index) {
        int due = -1;
        for (GameObject o : scenery) {
            int r = removal(px, pz, gridRadius, o.x(), o.z(), SCENERY);
            if (r == CLEAR) {
                return CLEAR;
            }
            due = later(due, r);
        }
        for (WallObject w : walls) {
            int r = removal(px, pz, gridRadius, w.x(), w.z(), WALLS);
            if (r == CLEAR) {
                return CLEAR;
            }
            due = later(due, r);
        }
        for (GroundItem g : items) {
            int r = removal(px, pz, gridRadius, g.x(), g.z(), GROUND_ITEMS);
            if (r == CLEAR) {
                return CLEAR;
            }
            due = later(due, r);
        }
        int pcx = px >> 3;
        int pcz = pz >> 3;
        LongSet heldScenery = null;
        LongSet heldWalls = null;
        for (int cx = pcx - gridRadius; cx <= pcx + gridRadius; cx++) {
            for (int cz = pcz - gridRadius; cz <= pcz + gridRadius; cz++) {
                if (!entering.test(cx, cz)) {
                    continue;
                }
                if (heldScenery == null) {
                    heldScenery = new LongOpenHashSet();
                    for (GameObject o : scenery) {
                        heldScenery.add(chunkKey(o.x() >> 3, o.z() >> 3));
                    }
                    heldWalls = new LongOpenHashSet();
                    for (WallObject w : walls) {
                        heldWalls.add(chunkKey(w.x() >> 3, w.z() >> 3));
                    }
                }
                long key = chunkKey(cx, cz);
                if (wireRank(due) < wireRank(WALLS) && !heldWalls.contains(key) && index.hasBoundaryInChunk(cx, cz)) {
                    due = WALLS;
                }
                if (wireRank(due) < wireRank(SCENERY)
                        && !heldScenery.contains(key)
                        && index.hasSceneryInChunk(cx, cz)) {
                    due = SCENERY;
                }
            }
        }
        return due;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static int removal(int px, int pz, int gridRadius, int x, int z, int own) {
        int dcx = (x >> 3) - (px >> 3);
        int dcz = (z >> 3) - (pz >> 3);
        if (dcx <= gridRadius && dcx >= -gridRadius && dcz <= gridRadius && dcz >= -gridRadius) {
            return -1;
        }
        int dx = x - px;
        int dz = z - pz;
        boolean near = dx > -REMOVE_OFFSET_LIMIT
                && dz > -REMOVE_OFFSET_LIMIT
                && dx < REMOVE_OFFSET_LIMIT
                && dz < REMOVE_OFFSET_LIMIT;
        return near ? own : CLEAR;
    }

    private static int later(int a, int b) {
        return wireRank(b) > wireRank(a) ? b : a;
    }
}
