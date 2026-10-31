package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.probe.NavProbe;
import openrsc.nav.walk.StepView;
import openrsc.nav.walk.WindowSearch;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalFieldTest {

    // Pairs across the y = 384 seam, the x = 256 seam and the cluster corner at (192, 320), then two
    // inside one cluster.
    private static final int[][] LOCAL_PAIRS = {
        {230, 375, 250, 392},
        {237, 384, 255, 386},
        {225, 368, 256, 386},
        {186, 314, 198, 325},
        {184, 318, 200, 322},
        {188, 312, 196, 328},
        {180, 330, 205, 310},
        {250, 380, 262, 390},
        {120, 650, 128, 660},
        {132, 640, 141, 655},
    };

    private static ServerData env;
    private static CollisionMap map;
    private static Pathfinder pf;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        map = env.collisionMap();
        pf = RealDefs.cachedPathfinder();
    }

    @Test
    @DisplayName("a local walk is priced at its exact tile count, seams included")
    void aLocalWalkIsPricedExactly() {
        StringBuilder off = new StringBuilder();
        for (int[] p : LOCAL_PAIRS) {
            int bfs = bfs(Point.of(p[0], p[1]), Point.of(p[2], p[3]));
            int est = estimate(p, false);
            if (est != bfs * 10) {
                off.append(String.format(
                        "%n  %s: estimate %d, walk %d tiles (%+d deci)", leg(p), est, bfs, est - bfs * 10));
            }
        }
        assertTrue(off.isEmpty(), "local pairs priced off their walk:" + off);
    }

    @Test
    @DisplayName("plan and estimate agree on a local pair")
    void planAndEstimateAgreeLocally() {
        for (int[] p : LOCAL_PAIRS) {
            for (boolean danger : new boolean[] {false, true}) {
                AccountFacts facts = NavProbe.onFootFacts(env, p[0], p[1]);
                NavPolicy pol = NavProbe.policyFor(
                        facts,
                        (danger ? NavPolicy.DEFAULT : NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF)));
                PlanResult r = pf.nav()
                        .plan(new PlanRequest(
                                facts, Point.of(p[0], p[1]), Point.of(p[2], p[3]), LoadoutSpec.any(), pol));
                assertTrue(r instanceof PlanResult.Planned, leg(p) + " plans");
                assertEquals(
                        estimate(p, danger),
                        ((PlanResult.Planned) r).route().totalCostDeci(),
                        leg(p) + " danger=" + danger);
            }
        }
    }

    @Test
    @DisplayName("a route through a special door is priced exactly")
    void aRouteThroughASpecialDoorIsPricedExactly() {
        List<Transport> doors = new ArrayList<>();
        for (Transport t : pf.transports()) {
            // By constant, not by a name fragment, which would also catch MINING_GUILD_LADDER.
            if (t.costOverrideKey().equals(openrsc.nav.transports.SpecialDoor.DOOR_MINING_GUILD.name())) {
                doors.add(t);
            }
        }
        assertEquals(2, doors.size(), "the Mining Guild door, both ways: " + doors);
        List<Point> inside = new ArrayList<>();
        List<Point> outside = new ArrayList<>();
        for (int y = 3364; y <= 3399; y++) {
            for (int x = 263; x <= 276; x++) {
                if (!map.fullyBlocked(x, y)) {
                    (y < 3381 ? inside : outside).add(Point.of(x, y));
                }
            }
        }
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < inside.size(); i += 7) {
            for (int j = 0; j < outside.size(); j += 7) {
                pairs.add(new int[] {
                    inside.get(i).x(),
                    inside.get(i).y(),
                    outside.get(j).x(),
                    outside.get(j).y()
                });
                pairs.add(new int[] {
                    outside.get(j).x(),
                    outside.get(j).y(),
                    inside.get(i).x(),
                    inside.get(i).y()
                });
            }
        }
        int throughTheDoor = 0;
        for (int[] p : pairs) {
            Point from = Point.of(p[0], p[1]);
            Point goal = Point.of(p[2], p[3]);
            int truth = Integer.MAX_VALUE;
            int walk = bfs(from, goal);
            if (walk >= 0) {
                truth = walk * 10;
            }
            for (Transport door : doors) {
                for (Point entry : door.entries()) {
                    int in = bfs(from, entry);
                    int out = bfs(door.destinationFrom(entry), goal);
                    if (in >= 0 && out >= 0 && in * 10 + door.cost() + out * 10 < truth) {
                        truth = in * 10 + door.cost() + out * 10;
                        throughTheDoor++;
                    }
                }
            }
            if (truth == Integer.MAX_VALUE) {
                continue;
            }
            AccountFacts facts = NavProbe.allEligibleFacts(pf, from.x(), from.y());
            int est = pf.nav()
                    .estimate(
                            facts,
                            from,
                            goal,
                            NavProbe.policyFor(facts, NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF)))
                    .deci();
            assertEquals(truth, est, leg(p) + " through the Mining Guild door");
        }
        assertTrue(throughTheDoor >= 10, "pairs best through the door: " + throughTheDoor);
    }

    @Test
    @DisplayName("the walker's search reaches every local goal the field prices")
    void theWalkerReachesWhatTheFieldPrices() {
        int[][] ardougne = {{591, 603, 620, 581}, {600, 591, 616, 576}, {584, 619, 580, 606}};
        WindowSearch search = new WindowSearch(map);
        for (int[] p : ardougne) {
            Point from = Point.of(p[0], p[1]);
            Point goal = Point.of(p[2], p[3]);
            int est = estimate(p, false);
            assertTrue(est > 0, leg(p) + " is priced: " + est);
            List<Point> path = search.run(
                    StepView.ofMap(map, Planner.DangerCharge.NONE),
                    from,
                    Rect.of(goal, goal),
                    null,
                    null,
                    true,
                    WindowSearch.Momentum.NONE);
            assertTrue(
                    path != null && path.size() >= 2,
                    leg(p) + " priced " + est + " but the walker's window holds no walk to it");
            assertEquals(est, (path.size() - 1) * 10, leg(p) + ": the walk the search found");
        }
    }

    @Test
    @Disabled("wall-clock budget: depends on the machine and its load (3.5 ms on a busy build agent); run by hand")
    @DisplayName("a window builds in well under a millisecond")
    void aWindowBuildsFast() {
        Point from = Point.of(237, 384);
        StepMasks steps = new StepMasks(map);
        LocalField.build(steps, map, null, from, LocalField.MAX_RADIUS);
        long t0 = System.nanoTime();
        int n = 50;
        for (int i = 0; i < n; i++) {
            LocalField.build(steps, map, null, from, LocalField.MAX_RADIUS);
        }
        double perBuildMs = (System.nanoTime() - t0) / 1e6 / n;
        assertTrue(perBuildMs < 2.0, "a full window took " + perBuildMs + " ms to build");
    }

    private static int estimate(int[] p, boolean danger) {
        AccountFacts facts = NavProbe.onFootFacts(env, p[0], p[1]);
        return pf.nav()
                .estimate(
                        facts,
                        Point.of(p[0], p[1]),
                        Point.of(p[2], p[3]),
                        NavProbe.policyFor(
                                facts,
                                (danger
                                        ? NavPolicy.DEFAULT
                                        : NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF))))
                .deci();
    }

    // Reused across calls, a cell unvisited unless its stamp is this call's: clearing a map-sized array
    // per call cost more than the searches.
    private static int[] bfsDist;

    private static int[] bfsStamp;
    private static int bfsGeneration;

    private static int bfs(Point from, Point to) {
        int w = map.width();
        if (bfsDist == null) {
            bfsDist = new int[w * map.height()];
            bfsStamp = new int[w * map.height()];
        }
        int gen = ++bfsGeneration;
        ArrayDeque<Integer> q = new ArrayDeque<>();
        int start = from.y() * w + from.x();
        bfsDist[start] = 0;
        bfsStamp[start] = gen;
        q.add(start);
        while (!q.isEmpty()) {
            int cur = q.poll();
            int x = cur % w;
            int y = cur / w;
            if (x == to.x() && y == to.y()) {
                return bfsDist[cur];
            }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if ((dx == 0 && dy == 0) || !map.inBounds(nx, ny) || !map.canStep(x, y, nx, ny, Overlays.NONE)) {
                        continue;
                    }
                    int ni = ny * w + nx;
                    if (bfsStamp[ni] != gen) {
                        bfsStamp[ni] = gen;
                        bfsDist[ni] = bfsDist[cur] + 1;
                        q.add(ni);
                    }
                }
            }
        }
        return -1;
    }

    private static String leg(int[] p) {
        return p[0] + "," + p[1] + " -> " + p[2] + "," + p[3];
    }
}
