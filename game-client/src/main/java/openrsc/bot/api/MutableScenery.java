package openrsc.bot.api;

import java.util.Collection;
import java.util.function.Function;
import openrsc.gamedata.geometry.Rect;
import org.jetbrains.annotations.Nullable;

public final class MutableScenery {

    private final ChunkMap<GameObject> objects = new ChunkMap<>(1);
    private final Function<GameObject, Rect> footprint;

    private Scenery.@Nullable Streamed streamed;

    MutableScenery(Function<GameObject, Rect> footprint) {
        this.footprint = footprint;
    }

    @Nullable
    GameObject update(int id, int x, int z, int dir) {
        if (id == MutableWorld.REMOVED) {
            return objects.remove(x, z, 0);
        }
        return objects.put(x, z, 0, new GameObject(id, x, z, dir));
    }

    @Nullable
    GameObject at(int x, int z) {
        return objects.get(x, z, 0);
    }

    int clearChunk(int cx, int cz) {
        return objects.clearChunk(cx, cz).size();
    }

    Collection<GameObject> all() {
        return objects.freeze().values();
    }

    Scenery.Streamed freeze() {
        ChunkMap.Frozen<GameObject> told = objects.freeze();
        Scenery.Streamed s = streamed;
        if (s == null || s.told != told) {
            s = new Scenery.Streamed(told, footprint);
            streamed = s;
        }
        return s;
    }
}
