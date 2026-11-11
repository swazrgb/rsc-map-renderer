package openrsc.bot.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.world.ChunkBox;
import openrsc.gamedata.world.StaticLocIndex;
import openrsc.nav.NavPolicy;
import org.jetbrains.annotations.Nullable;

public final class Walls {

    private final ChunkMap.Frozen<WallObject> told;
    private final ChunkBox box;
    private final StaticLocIndex statics;
    private final @Nullable GameState owner;

    Walls(ChunkMap.Frozen<WallObject> told, ChunkBox box, StaticLocIndex statics, @Nullable GameState owner) {
        this.told = told;
        this.box = box;
        this.statics = statics;
        this.owner = owner;
    }

    Walls boundTo(GameState state) {
        return new Walls(told, box, statics, state);
    }

    public @Nullable WallObject at(int x, int z, int dir) {
        if (dir < 0 || dir >= MutableWalls.DIRECTIONS) {
            return null;
        }
        WallObject sent = told.get(x, z, dir);
        if (sent != null || box.isLive(x, z)) {
            return sent;
        }
        int h = statics.boundaryAt(x, z, dir);
        return h < 0 ? null : new WallObject(statics.boundaryId(h), x, z, dir);
    }

    public List<WallObject> at(int x, int z) {
        List<WallObject> found = new ArrayList<>(1);
        for (int dir = 0; dir < MutableWalls.DIRECTIONS; dir++) {
            WallObject wall = at(x, z, dir);
            if (wall != null) {
                found.add(wall);
            }
        }
        return found;
    }

    public @Nullable WallObject at(Point at, int... ids) {
        for (int dir = 0; dir < MutableWalls.DIRECTIONS; dir++) {
            WallObject wall = at(at.x(), at.y(), dir);
            if (wall != null && Ids.contains(ids, wall.id())) {
                return wall;
            }
        }
        return null;
    }

    public @Nullable WallObject nearest(NavPolicy policy, int... ids) {
        if (owner == null) {
            throw new IllegalStateException("a wall view with no tick cannot price a walk");
        }
        return NearestTargets.nearest(
                owner, policy, Targetable.Kind.BOUNDARY, ids, told.values(), box, WallObject::new);
    }

    public Collection<WallObject> inView() {
        return told.values();
    }
}
