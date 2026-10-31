package openrsc.nav.bench;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Planner;
import openrsc.nav.Transport;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCacheBudget;
import openrsc.nav.eligibility.RouteContext;
import openrsc.nav.probe.NavProbe;

/**
 * Measures how much slower than the best possible trip a walk is: random trips for a fresh account,
 * each simulated tick by tick with {@link NavProbe#planBetween}, held against an exact search over
 * every tile and every transport that account may take for free (no prerequisite it fails, nothing
 * consumed). Uses only the public {@link Pathfinder} API.
 *
 * <p>Arguments: {@code fastest|danger [trips] [seed]}, defaulting to fastest, 150 and 11.
 * {@code fastest} runs with danger avoidance off, and the reference is the quickest trip: 10
 * deci-ticks a step plus each transport's cost. {@code danger} runs with it on, and the reference
 * picks its transports by the planner's weight (a tile's danger charge on entering it, a transport's
 * at its landing), then walks between them by duration, so a deliberate detour round danger is not
 * counted as waste. It also prints how far the weight of the transports the planner chose is from
 * the optimum.
 */
public final class WalkReference {

    private static final int W = CollisionMap.WIDTH;
    private static final int H = CollisionMap.HEIGHT;
    private static final int[] DX = {-1, 0, 1, -1, 0, 1, -1, 0, 1};
    private static final int[] DY = {-1, -1, -1, 0, 0, 0, 1, 1, 1};
    /** In deci-ticks; a trip priced past it takes a transport costed as a last resort, and is skipped. */
    private static final int LAST_RESORT = 100_000;

    private record Edge(int to, int cost, String name) {}

    private final ServerData env;
    private final Pathfinder pf;
    private final CollisionMap map;
    /** Per tile, bit {@code (dy+1)*3 + (dx+1)} is set when the step to that neighbour is legal. */
    private final short[] out = new short[W * H];

    private final boolean dangerOn;
    private final NavPolicy policy;
    private final Planner.DangerCharge charge;
    private final Map<Integer, List<Edge>> transportsInto = new HashMap<>();
    private final Map<Integer, List<Edge>> transportsOutOf = new HashMap<>();
    private final int[] dist = new int[W * H];
    private final int[] seen = new int[W * H];
    private final int[] steps = new int[W * H];
    private int generation;

    private WalkReference(ServerData env, Pathfinder pf, boolean dangerOn) {
        this.env = env;
        this.pf = pf;
        this.map = env.collisionMap();
        this.dangerOn = dangerOn;
        this.policy = dangerOn ? NavPolicy.DEFAULT : NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF);
        AccountFacts fresh = NavProbe.onFootFacts(env, 0, 0);
        this.charge = dangerOn ? pf.nav().dangerCharge(fresh, policy) : Planner.DangerCharge.NONE;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (!map.inBounds(x, y)) {
                    continue;
                }
                int mask = 0;
                for (int d = 0; d < 9; d++) {
                    int nx = x + DX[d];
                    int ny = y + DY[d];
                    if (d != 4 && map.inBounds(nx, ny) && map.canStep(x, y, nx, ny, Overlays.NONE)) {
                        mask |= 1 << d;
                    }
                }
                out[y * W + x] = (short) mask;
            }
        }
        // Teleports are left out: they work from anywhere in a region, not from an entry tile.
        RouteContext ctx = new RouteContext(fresh, policy, env);
        for (Transport t : pf.transports()) {
            if (t.entryFree() || !t.consumes(fresh).isEmpty() || !t.prereq().test(ctx)) {
                continue;
            }
            for (Point e : t.entries()) {
                Point d = t.destinationFrom(e);
                if (d == null || !map.inBounds(d.x(), d.y()) || !map.inBounds(e.x(), e.y())) {
                    continue;
                }
                int ei = e.y() * W + e.x();
                int di = d.y() * W + d.x();
                transportsInto.computeIfAbsent(di, k -> new ArrayList<>()).add(new Edge(ei, t.cost(), t.name()));
                transportsOutOf.computeIfAbsent(ei, k -> new ArrayList<>()).add(new Edge(di, t.cost(), t.name()));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        boolean dangerOn =
                switch (args.length > 0 ? args[0] : "fastest") {
                    case "fastest" -> false;
                    case "danger" -> true;
                    default -> throw new IllegalArgumentException("mode: fastest | danger");
                };
        int trips = args.length > 1 ? Integer.parseInt(args[1]) : 150;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 11L;
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        ServerData env;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            env = ServerData.load(conf, world, landscape);
        }
        Pathfinder pf = Pathfinder.load(env, new CaffeineNavCaches(NavCacheBudget.DEFAULTS));
        new WalkReference(env, pf, dangerOn).run(trips, seed);
    }

    private void run(int trips, long seed) {
        SplittableRandom rnd = new SplittableRandom(seed);
        List<Integer> over = new ArrayList<>();
        List<Integer> weightGaps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        long overTotal = 0;
        long referenceTotal = 0;
        int compared = 0;
        int exact = 0;
        int sameTransports = 0;
        int attempts = 0;
        while (compared < trips && attempts++ < trips * 20) {
            int goal = randomTile(rnd);
            settleTo(goal);
            int start = -1;
            for (int k = 0; k < 50 && start < 0; k++) {
                int c = randomTile(rnd);
                if (dist[c] != Integer.MAX_VALUE && c != goal) {
                    start = c;
                }
            }
            if (start < 0) {
                continue;
            }
            Reference ref = referenceFrom(start, goal);
            if (ref == null || ref.ticks >= LAST_RESORT / 10) {
                continue;
            }
            int sx = start % W;
            int sy = start / W;
            int gx = goal % W;
            int gy = goal / W;
            String trip = "(" + sx + "," + sy + ") -> (" + gx + "," + gy + ")";
            List<NavProbe.ProbeStep> walk = NavProbe.planBetween(
                    pf,
                    sx,
                    sy,
                    gx,
                    gy,
                    NavProbe.onFootFacts(env, sx, sy),
                    policy,
                    LoadoutSpec.any(),
                    new NavProbe.SimulationStats());
            NavProbe.ProbeStep last = walk == null ? null : walk.get(walk.size() - 1);
            if (last == null || last.x() != gx || last.y() != gy) {
                notes.add((walk == null ? "NO PLAN " : "DID NOT ARRIVE ") + trip);
                continue;
            }
            Walked walked = walked(walk, start, goal);
            compared++;
            int extra = walked.ticks - ref.ticks;
            over.add(extra);
            overTotal += extra;
            referenceTotal += ref.ticks;
            exact += extra == 0 ? 1 : 0;
            sameTransports += walked.transports.equals(ref.transports) ? 1 : 0;
            if (walked.weight >= 0) {
                weightGaps.add((int) (walked.weight - dist[start]));
            }
            if (Math.abs(extra) >= 5) {
                notes.add(String.format(
                        "%+d ticks %s: best %d, walked %d%n    best via %s%n    walked via %s",
                        extra, trip, ref.ticks, walked.ticks, ref.transports, walked.transports));
            }
        }
        Collections.sort(over);
        System.out.printf(
                "%s: %d trips, %d exact, %d with the reference's transports, %+d ticks in "
                        + "total (%.2f%% of %d); per trip p10 %+d, p50 %+d, p90 %+d, worst %+d / %+d%n",
                dangerOn ? "danger" : "fastest",
                compared,
                exact,
                sameTransports,
                overTotal,
                100.0 * overTotal / Math.max(1, referenceTotal),
                referenceTotal,
                pct(over, 10),
                pct(over, 50),
                pct(over, 90),
                over.isEmpty() ? 0 : over.get(0),
                over.isEmpty() ? 0 : over.get(over.size() - 1));
        if (dangerOn && !weightGaps.isEmpty()) {
            Collections.sort(weightGaps);
            System.out.printf(
                    "  chosen transports' weight over the optimum (deci): %d optimal of %d, "
                            + "p50 %d, p90 %d, worst %d%n",
                    weightGaps.stream().filter(g -> g == 0).count(),
                    weightGaps.size(),
                    pct(weightGaps, 50),
                    pct(weightGaps, 90),
                    weightGaps.get(weightGaps.size() - 1));
        }
        notes.forEach(System.out::println);
    }

    private static int pct(List<Integer> sorted, int p) {
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, sorted.size() * p / 100));
    }

    private int randomTile(SplittableRandom rnd) {
        while (true) {
            int x = rnd.nextInt(W);
            int y = rnd.nextInt(H);
            if (map.inBounds(x, y) && !map.fullyBlocked(x, y) && out[y * W + x] != 0) {
                return y * W + x;
            }
        }
    }

    private record Reference(int ticks, List<String> transports) {}

    private record Walked(int ticks, List<String> transports, long weight) {}

    /**
     * Fills {@link #dist} with every tile's least weight to {@code goal}, in deci-ticks, searching
     * backwards over steps and the free transports. Without danger the weight is the duration.
     */
    private void settleTo(int goal) {
        Arrays.fill(dist, Integer.MAX_VALUE);
        long[] heap = new long[1 << 16];
        int size = 0;
        dist[goal] = 0;
        heap[size++] = goal;
        while (size > 0) {
            long top = heap[0];
            if (--size > 0) {
                heap[0] = heap[size];
                siftDown(heap, size);
            }
            int c = (int) top;
            int d = (int) (top >>> 32);
            if (d > dist[c]) {
                continue;
            }
            int cx = c % W;
            int cy = c / W;
            int enter = charge.at(cx, cy);
            for (int k = 0; k < 9; k++) {
                int nx = cx - DX[k];
                int ny = cy - DY[k];
                if (k == 4 || !map.inBounds(nx, ny) || (out[ny * W + nx] & (1 << k)) == 0) {
                    continue;
                }
                long nd = (long) d + 10 + enter;
                int n = ny * W + nx;
                if (nd < dist[n]) {
                    dist[n] = (int) nd;
                    heap = push(heap, size++, (nd << 32) | n);
                }
            }
            for (Edge e : transportsInto.getOrDefault(c, List.of())) {
                long nd = (long) d + e.cost + enter;
                if (nd < dist[e.to]) {
                    dist[e.to] = (int) nd;
                    heap = push(heap, size++, (nd << 32) | e.to);
                }
            }
        }
    }

    /**
     * Follows the least-weight way from {@code start} that {@link #settleTo} found, and times the walks
     * between its transports by plain duration. {@code null} when a leg cannot be walked.
     */
    private Reference referenceFrom(int start, int goal) {
        List<String> transports = new ArrayList<>();
        long ticks = 0;
        int at = start;
        int legFrom = start;
        while (at != goal) {
            int next = -1;
            Edge took = null;
            for (Edge e : transportsOutOf.getOrDefault(at, List.of())) {
                if (dist[e.to] != Integer.MAX_VALUE
                        && (long) dist[e.to] + e.cost + charge.at(e.to % W, e.to / W) == dist[at]) {
                    next = e.to;
                    took = e;
                    break;
                }
            }
            int ax = at % W;
            int ay = at / W;
            for (int k = 0; k < 9 && next < 0; k++) {
                if ((out[at] & (1 << k)) == 0) {
                    continue;
                }
                int nx = ax + DX[k];
                int ny = ay + DY[k];
                int n = ny * W + nx;
                if (dist[n] != Integer.MAX_VALUE && (long) dist[n] + 10 + charge.at(nx, ny) == dist[at]) {
                    next = n;
                }
            }
            if (next < 0) {
                return null;
            }
            if (took != null) {
                long leg = walk(legFrom, at, false);
                if (leg < 0) {
                    return null;
                }
                ticks += leg / 10 + took.cost / 10;
                transports.add(took.name);
                legFrom = next;
            }
            at = next;
        }
        long leg = walk(legFrom, goal, false);
        return leg < 0 ? null : new Reference((int) (ticks + leg / 10), transports);
    }

    /** The simulated walk's ticks and transports, and with danger on the least weight of its legs. */
    private Walked walked(List<NavProbe.ProbeStep> walk, int start, int goal) {
        int ticks = 0;
        long weight = 0;
        int legFrom = start;
        List<String> transports = new ArrayList<>();
        for (int k = 1; k < walk.size(); k++) {
            NavProbe.ProbeStep s = walk.get(k);
            if (s.hopName() == null) {
                ticks++;
                continue;
            }
            int cost = s.hopCost() == null ? 0 : s.hopCost();
            ticks += cost / 10;
            transports.add(s.hopName());
            NavProbe.ProbeStep before = walk.get(k - 1);
            long leg = walk(legFrom, before.y() * W + before.x(), true);
            weight = leg < 0 || weight < 0 ? -1 : weight + leg + cost + charge.at(s.x(), s.y());
            legFrom = s.y() * W + s.x();
        }
        long leg = walk(legFrom, goal, true);
        weight = leg < 0 || weight < 0 ? -1 : weight + leg;
        return new Walked(ticks, transports, dangerOn ? weight : -1);
    }

    /**
     * A* on foot from {@code a} to {@code b}, in deci-ticks: the least weight when {@code charged},
     * else the duration. -1 when {@code b} cannot be walked to.
     */
    private long walk(int a, int b, boolean charged) {
        if (a == b) {
            return 0;
        }
        if (++generation == Integer.MAX_VALUE) {
            Arrays.fill(seen, 0);
            generation = 1;
        }
        int bx = b % W;
        int by = b / W;
        long[] heap = new long[1 << 12];
        int size = 0;
        steps[a] = 0;
        seen[a] = generation;
        heap[size++] = ((long) bound(a, bx, by) << 32) | a;
        while (size > 0) {
            long top = heap[0];
            if (--size > 0) {
                heap[0] = heap[size];
                siftDown(heap, size);
            }
            int c = (int) top;
            int g = steps[c];
            if ((int) (top >>> 32) > g + bound(c, bx, by)) {
                continue;
            }
            if (c == b) {
                return g;
            }
            int cx = c % W;
            int cy = c / W;
            for (int k = 0; k < 9; k++) {
                if ((out[c] & (1 << k)) == 0) {
                    continue;
                }
                int nx = cx + DX[k];
                int ny = cy + DY[k];
                int n = ny * W + nx;
                int nd = g + 10 + (charged ? charge.at(nx, ny) : 0);
                if (seen[n] == generation && steps[n] <= nd) {
                    continue;
                }
                seen[n] = generation;
                steps[n] = nd;
                heap = push(heap, size++, ((long) (nd + bound(n, bx, by)) << 32) | n);
            }
        }
        return -1;
    }

    private static int bound(int c, int bx, int by) {
        return 10 * Math.max(Math.abs(c % W - bx), Math.abs(c / W - by));
    }

    private static long[] push(long[] heap, int i, long v) {
        if (i == heap.length) {
            heap = Arrays.copyOf(heap, i * 2);
        }
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heap[p] <= v) {
                break;
            }
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = v;
        return heap;
    }

    private static void siftDown(long[] heap, int n) {
        long v = heap[0];
        int i = 0;
        while (true) {
            int l = 2 * i + 1;
            if (l >= n) {
                break;
            }
            int r = l + 1;
            int c = r < n && heap[r] < heap[l] ? r : l;
            if (heap[c] >= v) {
                break;
            }
            heap[i] = heap[c];
            i = c;
        }
        heap[i] = v;
    }
}
