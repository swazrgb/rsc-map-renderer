package openrsc.bot.api;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collection;
import java.util.function.Function;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.world.ChunkBox;
import openrsc.gamedata.world.StaticLocIndex;
import openrsc.nav.NavPolicy;
import org.jetbrains.annotations.Nullable;

public final class Scenery {

    private final Streamed streamed;
    private final ChunkMap.Frozen<GameObject> told;
    private final ChunkBox box;
    private final StaticLocIndex statics;
    private final @Nullable GameState owner;

    static final class Streamed {
        final ChunkMap.Frozen<GameObject> told;
        private final Function<GameObject, Rect> footprint;
        private volatile @Nullable Long2ObjectMap<GameObject> covering;

        Streamed(ChunkMap.Frozen<GameObject> told, Function<GameObject, Rect> footprint) {
            this.told = told;
            this.footprint = footprint;
        }

        Long2ObjectMap<GameObject> covering() {
            Long2ObjectMap<GameObject> held = covering;
            if (held == null) {
                held = new Long2ObjectOpenHashMap<>(told.size() * 2);
                for (GameObject o : told.values()) {
                    Rect r = footprint.apply(o);
                    for (int y = r.minY(); y <= r.maxY(); y++) {
                        for (int x = r.minX(); x <= r.maxX(); x++) {
                            held.putIfAbsent(Tiles.tile(x, y), o);
                        }
                    }
                }
                covering = held;
            }
            return held;
        }
    }

    Scenery(Streamed streamed, ChunkBox box, StaticLocIndex statics, @Nullable GameState owner) {
        this.streamed = streamed;
        this.told = streamed.told;
        this.box = box;
        this.statics = statics;
        this.owner = owner;
    }

    Scenery boundTo(GameState state) {
        return new Scenery(streamed, box, statics, state);
    }

    public @Nullable GameObject at(Point p) {
        return at(p.x(), p.y());
    }

    public @Nullable GameObject at(int x, int z) {
        GameObject sent = told.get(x, z, 0);
        if (sent != null || box.isLive(x, z)) {
            return sent;
        }
        int h = statics.sceneryAt(x, z);
        return h < 0 ? null : fromStatic(h);
    }

    public @Nullable GameObject at(Point p, int... ids) {
        GameObject o = at(p.x(), p.y());
        return o != null && Ids.contains(ids, o.id()) ? o : null;
    }

    public @Nullable GameObject covering(int x, int z) {
        GameObject sent = streamed.covering().get(Tiles.tile(x, z));
        if (sent != null || box.isLive(x, z)) {
            return sent;
        }
        int h = statics.sceneryCovering(x, z);
        if (h < 0) {
            return null;
        }
        int ax = statics.sceneryX(h), az = statics.sceneryY(h);
        return box.isLive(ax, az) || told.get(ax, az, 0) != null ? null : fromStatic(h);
    }

    public @Nullable GameObject nearest(NavPolicy policy, int... ids) {
        if (owner == null) {
            throw new IllegalStateException("a scenery view with no tick cannot price a walk");
        }
        return NearestTargets.nearest(owner, policy, Targetable.Kind.SCENERY, ids, told.values(), box, GameObject::new);
    }

    public Collection<GameObject> inView() {
        return told.values();
    }

    private GameObject fromStatic(int h) {
        return new GameObject(statics.sceneryId(h), statics.sceneryX(h), statics.sceneryY(h), statics.sceneryDir(h));
    }
}
