package openrsc.bot.api;

import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import org.jetbrains.annotations.Nullable;

public final class GroundItems implements Iterable<GroundItem> {

    private final ChunkMap.Frozen<List<GroundItem>> byTile;
    private final @Nullable GameState owner;
    private volatile int total = -1;

    GroundItems(ChunkMap.Frozen<List<GroundItem>> byTile) {
        this(byTile, null);
    }

    private GroundItems(ChunkMap.Frozen<List<GroundItem>> byTile, @Nullable GameState owner) {
        this.byTile = byTile;
        this.owner = owner;
    }

    GroundItems boundTo(GameState state) {
        return new GroundItems(byTile, state);
    }

    ChunkMap.Frozen<List<GroundItem>> frozen() {
        return byTile;
    }

    public List<GroundItem> at(int x, int z) {
        List<GroundItem> here = byTile.get(x, z, 0);
        return here == null ? List.of() : here;
    }

    public List<GroundItem> at(Point p) {
        return at(p.x(), p.y());
    }

    public @Nullable GroundItem at(Point p, int... ids) {
        for (GroundItem g : at(p.x(), p.y())) {
            if (Ids.contains(ids, g.id())) {
                return g;
            }
        }
        return null;
    }

    public @Nullable GroundItem nearest(NavPolicy policy, int... ids) {
        if (owner == null) {
            throw new IllegalStateException("a ground-item view with no tick cannot price a walk");
        }
        return NearestTargets.nearestInView(owner, policy, ids, this);
    }

    public int size() {
        int n = total;
        if (n < 0) {
            n = 0;
            for (List<GroundItem> here : byTile.values()) {
                n += here.size();
            }
            total = n;
        }
        return n;
    }

    public boolean isEmpty() {
        return byTile.size() == 0;
    }

    public Stream<GroundItem> stream() {
        return byTile.values().stream().flatMap(List::stream);
    }

    @Override
    public Iterator<GroundItem> iterator() {
        return stream().iterator();
    }
}
