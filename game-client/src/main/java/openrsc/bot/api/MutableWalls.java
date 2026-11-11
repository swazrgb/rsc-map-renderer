package openrsc.bot.api;

import java.util.Collection;

public final class MutableWalls {

    static final int DIRECTIONS = 4;

    private final ChunkMap<WallObject> walls = new ChunkMap<>(DIRECTIONS);

    void update(int id, int x, int z, int dir) {
        if (dir < 0 || dir >= DIRECTIONS) {
            return;
        }
        if (id == MutableWorld.REMOVED) {
            walls.remove(x, z, dir);
            return;
        }
        walls.put(x, z, dir, new WallObject(id, x, z, dir));
    }

    int clearChunk(int cx, int cz) {
        return walls.clearChunk(cx, cz).size();
    }

    Collection<WallObject> all() {
        return walls.freeze().values();
    }

    ChunkMap.Frozen<WallObject> freeze() {
        return walls.freeze();
    }
}
