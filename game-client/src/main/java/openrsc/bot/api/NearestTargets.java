package openrsc.bot.api;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.world.ChunkBox;
import openrsc.nav.NavPolicy;
import openrsc.nav.Planner;
import org.jetbrains.annotations.Nullable;

final class NearestTargets {

    private NearestTargets() {}

    @FunctionalInterface
    interface Make<T> {
        T make(int id, int x, int z, int dir);
    }

    static <T extends Targetable> @Nullable T nearest(
            GameState state,
            NavPolicy policy,
            Targetable.Kind kind,
            int[] ids,
            Collection<T> streamed,
            ChunkBox box,
            Make<T> make) {
        List<T> live = new ArrayList<>();
        List<Dest> goals = new ArrayList<>();
        LongArrayList except = new LongArrayList();
        for (T t : streamed) {
            if (!box.isLive(t.x(), t.z())) {
                except.add(
                        kind == Targetable.Kind.SCENERY
                                ? Planner.StaticTargets.sceneryKey(t.x(), t.z())
                                : Planner.StaticTargets.boundaryKey(t.x(), t.z(), t.dir()));
            }
            if (!Ids.contains(ids, t.id())) {
                continue;
            }
            List<Point> tiles = state.serverData().collisionMap().engageTiles(t, state);
            if (tiles.isEmpty()) {
                continue;
            }
            live.add(t);
            goals.add(Dest.anyOf(tiles.toArray(new Area[0])));
        }
        Planner.Nearest n = state.nav()
                .nearest(
                        state,
                        state.pos(),
                        goals,
                        new Planner.StaticTargets(kind, ids, box, except.toLongArray()),
                        policy);
        if (n == null) {
            return null;
        }
        return n.live() >= 0 ? live.get(n.live()) : make.make(n.id(), n.x(), n.y(), n.dir());
    }

    static <T extends Targetable> @Nullable T nearestInView(
            GameState state, NavPolicy policy, int[] ids, Iterable<T> candidates) {
        List<T> live = new ArrayList<>();
        List<Dest> goals = new ArrayList<>();
        for (T t : candidates) {
            if (!Ids.contains(ids, t.id())) {
                continue;
            }
            List<Point> tiles = state.serverData().collisionMap().engageTiles(t, state);
            if (tiles.isEmpty()) {
                continue;
            }
            live.add(t);
            goals.add(Dest.anyOf(tiles.toArray(new Area[0])));
        }
        if (live.isEmpty()) {
            return null;
        }
        Planner.Nearest n = state.nav()
                .nearest(state, state.pos(), goals, Planner.StaticTargets.none(Targetable.Kind.SCENERY), policy);
        return n == null ? null : live.get(n.live());
    }
}
