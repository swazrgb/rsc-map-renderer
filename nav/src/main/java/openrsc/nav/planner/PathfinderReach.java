package openrsc.nav.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.TravelTimes;
import openrsc.nav.probe.NavProbe;

/**
 * Which walking components ({@link CollisionMap#componentId}) an account can get to from a start
 * tile, on foot or through any transport it may use, over every floor at once.
 *
 * <p>All tiles of a component connect on foot, so one tile stands for each and the answer is a
 * single batch estimate, one destination per component. The estimate takes no loadout.
 */
public final class PathfinderReach {

    private PathfinderReach() {}

    /**
     * The reachable component ids, ascending, out of {@code components} in all; empty when the start
     * is missing or off the map. {@code ms} is the wall time taken, in milliseconds.
     */
    public record Response(PathfinderPlans.Tile from, int[] reachable, int components, double ms) {}

    /**
     * Reachability for {@code req}'s start, account and policy; {@code req.to()} is not read. A
     * non-null {@code as} is planned as instead of {@code req.account()}.
     */
    public static Response of(Pathfinder pf, AccountFacts as, PathfinderPlans.Request req) {
        long t0 = System.nanoTime();
        ServerData env = pf.serverData();
        CollisionMap map = env.collisionMap();
        List<Point> tiles = representatives(map);
        PathfinderPlans.Tile from = req.from();
        if (from == null || !map.inBounds(from.x(), from.z())) {
            return new Response(from, new int[0], tiles.size(), ms(System.nanoTime() - t0));
        }
        AccountFacts facts = as != null ? as : PathfinderPlans.probeFacts(env, req.account());
        NavPolicy policy = NavProbe.policyFor(facts, PathfinderPlans.policyOf(req.policy()));
        AccountFacts here = NavProbe.factsFor(facts, pf, from.x(), from.z());
        TravelTimes costs = pf.nav().estimateBatch(here, Point.of(from.x(), from.z()), tiles, policy);
        int[] reachable = new int[tiles.size()];
        int n = 0;
        for (int i = 0; i < costs.size(); i++) {
            if (costs.get(i).isReachable()) {
                reachable[n++] = i + 1;
            }
        }
        return new Response(from, Arrays.copyOf(reachable, n), tiles.size(), ms(System.nanoTime() - t0));
    }

    private static CollisionMap lastMap;
    private static List<Point> lastTiles;

    /**
     * One tile of each component, the first in row order, at index {@code id - 1}. Remembered for the
     * last map asked about.
     */
    static synchronized List<Point> representatives(CollisionMap map) {
        if (map == lastMap) {
            return lastTiles;
        }
        int count = map.componentCount();
        int[] first = new int[count + 1];
        Arrays.fill(first, -1);
        int width = map.width();
        int found = 0;
        for (int y = 0; y < map.height() && found < count; y++) {
            for (int x = 0; x < width; x++) {
                int id = map.componentId(x, y);
                if (id > 0 && id <= count && first[id] < 0) {
                    first[id] = y * width + x;
                    found++;
                }
            }
        }
        List<Point> tiles = new ArrayList<>(count);
        for (int id = 1; id <= count; id++) {
            int t = first[id];
            // An id with no tile keeps its index with a stand-in.
            tiles.add(t < 0 ? Point.of(0, 0) : Point.of(t % width, t / width));
        }
        lastMap = map;
        lastTiles = List.copyOf(tiles);
        return lastTiles;
    }

    private static double ms(long nanos) {
        return Math.round(nanos / 10_000.0) / 100.0;
    }
}
