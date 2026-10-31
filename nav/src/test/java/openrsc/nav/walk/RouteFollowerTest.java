package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import openrsc.nav.engine.ClusterGeometry;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RouteFollowerTest {

    // Short legs that hug 64-tile cluster seams, and two long marches between gates.
    private static final int[][] LEGS = {
        {237, 384, 255, 386},
        {230, 375, 250, 392},
        {225, 368, 256, 386},
        {200, 340, 280, 430},
        {111, 142, 341, 487},
        {342, 487, 597, 859},
    };

    private static ServerData env;
    private static CollisionMap map;
    private static Pathfinder pf;
    private static NavPolicy dangerOff;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        map = env.collisionMap();
        pf = RealDefs.cachedPathfinder();
        dangerOff = NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF);
    }

    // A seam past the window's edge is crossed on a Chebyshev bound of the way beyond, blind to a wall
    // just past it.
    private static final int WINDOW_EDGE_ALLOWANCE = 1;

    @Test
    @DisplayName("a walk-only leg is walked in the grid's shortest step count, to the window's edge")
    void walksAreGridOptimal() {
        List<String> losses = new ArrayList<>();
        for (int[] leg : LEGS) {
            AccountFacts onFoot = NavProbe.onFootFacts(env, leg[0], leg[1]);
            List<NavProbe.ProbeStep> walk = NavProbe.planBetween(pf, leg[0], leg[1], leg[2], leg[3], onFoot, dangerOff);
            assertNotNull(walk, "no route " + Arrays.toString(leg));
            NavProbe.ProbeStep last = walk.get(walk.size() - 1);
            assertEquals(
                    leg[2] + "," + leg[3],
                    last.x() + "," + last.y(),
                    "the walk did not arrive " + Arrays.toString(leg));
            for (NavProbe.ProbeStep s : walk) {
                assertTrue(s.hopName() == null, "a walk-only leg took a hop: " + s.hopName());
            }
            int walked = walk.size() - 1;
            int[] fromStart = gridFrom(leg[0], leg[1]);
            int[] fromGoal = gridFrom(leg[2], leg[3]);
            int grid = fromStart[leg[3] * CollisionMap.WIDTH + leg[2]];
            if (walked > grid + WINDOW_EDGE_ALLOWANCE) {
                losses.add(Arrays.toString(leg) + ": walked " + walked + ", grid " + grid
                        + deviation(walk, fromStart, fromGoal, grid));
            }
        }
        assertTrue(losses.isEmpty(), String.join("\n", losses));
    }

    private static String deviation(List<NavProbe.ProbeStep> walk, int[] fromStart, int[] fromGoal, int grid) {
        for (int i = 0; i < walk.size(); i++) {
            NavProbe.ProbeStep s = walk.get(i);
            int t = s.y() * CollisionMap.WIDTH + s.x();
            if (fromStart[t] + fromGoal[t] > grid) {
                NavProbe.ProbeStep p = walk.get(i - 1);
                int cs = ClusterGeometry.CLUSTER_SIZE;
                return " — leaves every shortest walk at step " + i + ": (" + p.x() + "," + p.y() + ") -> ("
                        + s.x() + "," + s.y() + "), " + (fromStart[t] + fromGoal[t] - grid) + " over; x%"
                        + cs + "=" + s.x() % cs + ", y%" + cs + "=" + s.y() % cs;
            }
        }
        return " — every tile on a shortest walk (a wasted step lies between two)";
    }

    @Test
    @DisplayName("a walk never takes two steps where one legal step would do")
    void walksHaveNoSpikes() {
        for (int[] leg : LEGS) {
            AccountFacts onFoot = NavProbe.onFootFacts(env, leg[0], leg[1]);
            List<NavProbe.ProbeStep> walk = NavProbe.planBetween(pf, leg[0], leg[1], leg[2], leg[3], onFoot, dangerOff);
            assertNotNull(walk);
            for (int i = 0; i + 2 < walk.size(); i++) {
                NavProbe.ProbeStep a = walk.get(i);
                NavProbe.ProbeStep c = walk.get(i + 2);
                boolean oneStep = (a.x() == c.x() && a.y() == c.y()) || map.canStep(a.x(), a.y(), c.x(), c.y());
                assertTrue(
                        !oneStep,
                        "spike at step " + i + " of " + Arrays.toString(leg) + ": (" + a.x() + "," + a.y()
                                + ") -> (" + walk.get(i + 1).x() + ","
                                + walk.get(i + 1).y() + ") -> (" + c.x()
                                + "," + c.y() + ")");
            }
        }
    }

    @Test
    @DisplayName("a route with hops is walked through them to the goal")
    void hopsAreTakenOnTheWay() {
        AccountFacts onFoot = NavProbe.onFootFacts(env, 97, 100);
        List<NavProbe.ProbeStep> walk = NavProbe.planBetween(pf, 97, 100, 597, 859, onFoot, dangerOff);
        assertNotNull(walk);
        NavProbe.ProbeStep last = walk.get(walk.size() - 1);
        assertEquals("597,859", last.x() + "," + last.y(), "the walk did not arrive");
        List<String> hops = new ArrayList<>();
        for (NavProbe.ProbeStep s : walk) {
            if (s.hopName() != null) {
                hops.add(s.hopName());
            }
        }
        assertEquals(2, hops.size(), "the two wilderness/Taverley gates: " + hops);
    }

    @Test
    @DisplayName("a priced one-tile crossing is taken, not walked around")
    void aShortCrossingWithAWalkAroundIsStillTaken() {
        // An Underground Pass rock, whose landing is two tiles east of its entry.
        AccountFacts climber = NavProbe.allEligibleFacts(pf, 676, 3463);
        List<NavProbe.ProbeStep> walk = NavProbe.planBetween(pf, 676, 3463, 678, 3463, climber, dangerOff);
        assertNotNull(walk);
        List<String> hops = new ArrayList<>();
        for (NavProbe.ProbeStep s : walk) {
            if (s.hopName() != null) {
                hops.add(s.hopName());
            }
        }
        assertEquals(1, hops.size(), "the rock is climbed: " + hops);
        Transport rock = null;
        for (Transport t : openrsc.nav.transports.AllTransports.defaultRegistry(RealDefs.serverData())) {
            if (t.name().equals(hops.get(0))) {
                rock = t;
            }
        }
        assertNotNull(rock, hops.get(0));
        assertEquals(openrsc.nav.transports.AgilityObstacles.UNDERGROUND_PASS_ROCK_765.name(), rock.costOverrideKey());
        assertEquals(List.of(Point.of(676, 3463)), rock.entries());
        assertEquals(Point.of(678, 3463), rock.destinationFrom(Point.of(676, 3463)));
        assertTrue(walk.size() <= 3, "no walk-around: " + walk.size() + " steps");
    }

    @Test
    @DisplayName("a crossing the view reports open is walked past, not driven")
    void anOpenCrossingIsWalkedPast() {
        // The gate's declared landing (236,129) is blocked; the pit tile beside the entry is walled off
        // from it on the static map by the gate's edge.
        Point entry = Point.of(237, 130);
        Point pit = Point.of(236, 130);
        Route.Hop gate = new Route.Hop(
                openrsc.nav.transports.QuestObstacles.MAGE_ARENA_GATE_1019.name() + "@237_129#237_129",
                entry,
                Point.of(236, 129),
                Map.of(),
                Set.of(),
                Inventory.empty());
        Route route = new Route(List.of(gate, new Route.Arrive(pit, Inventory.empty())), 40, Map.of());
        StepView gateGone = new StepView() {
            @Override
            public boolean canStep(int fx, int fy, int tx, int ty) {
                boolean throughTheGate = fx == entry.x() && fy == entry.y() && tx == pit.x() && ty == pit.y();
                return throughTheGate || map.canStep(fx, fy, tx, ty);
            }

            @Override
            public boolean occupied(int x, int y) {
                return false;
            }

            @Override
            public int extra(int x, int y) {
                return 0;
            }

            @Override
            public boolean crossingOpen(Route.Hop hop) {
                return hop == gate;
            }
        };
        RouteFollower follower = new RouteFollower(map);
        RouteFollower.Aim closed = follower.aim(
                route, entry, StepView.ofMap(map, Planner.DangerCharge.NONE), WindowSearch.Momentum.NONE, null);
        assertTrue(closed instanceof RouteFollower.Delegate, "with the gate standing, the hop is driven");
        RouteFollower.Aim open = follower.aim(route, entry, gateGone, WindowSearch.Momentum.NONE, null);
        assertTrue(open instanceof RouteFollower.Walk, "with the gate gone, the walk goes past it");
        RouteFollower.Walk walk = (RouteFollower.Walk) open;
        assertNotNull(walk.path());
        assertEquals(pit, walk.path().get(1), "one step into the pit: " + walk.path());
    }

    @Test
    @DisplayName("a far hop is walked to like an arrival, and handed over where the view says")
    void aFarHopIsWalkedToUntilTheViewHandsItOver() {
        Point entry = Point.of(92, 649);
        Route.Hop toll = new Route.Hop(
                "AL_KHARID_BORDER#tollWest", entry, Point.of(91, 649), Map.of(), Set.of(), Inventory.empty());
        Route route = new Route(
                List.of(toll, new Route.Arrive(Rect.of(55, 686, 55, 686), Inventory.empty())), 2630, Map.of());
        RouteFollower follower = new RouteFollower(map);
        StepView view = StepView.ofMap(map, Planner.DangerCharge.NONE);
        Point falador = Point.of(288, 576);

        RouteFollower.Aim far = follower.aim(route, falador, view, WindowSearch.Momentum.NONE, null);
        assertTrue(far instanceof RouteFollower.Walk, "200 tiles off, the walk is the route's: " + far);
        RouteFollower.Walk walk = (RouteFollower.Walk) far;
        assertEquals(Rect.of(entry, entry), walk.target(), "aimed at the gate's entry");
        assertNull(walk.corridor(), "in the arrival's window, not one cut to the entry tile");
        assertTrue(
                follower.aim(route, falador, view, WindowSearch.Momentum.NONE, Rect.of(entry, entry))
                        instanceof RouteFollower.Walk,
                "and still walked while the host marches down the path it searched");
        List<Point> path = follower.search()
                .run(view, falador, walk.target(), walk.corridor(), walk.onward(), true, WindowSearch.Momentum.NONE);
        assertNotNull(path, "the window reaches the gate from Falador");
        assertTrue(path.get(1).y() >= falador.y(), "south, the way the trip priced — never north: " + path.get(1));

        assertTrue(
                follower.aim(route, entry, view, WindowSearch.Momentum.NONE, null) instanceof RouteFollower.Delegate,
                "on the entry, the script drives it");
        StepView handsOver = new StepView() {
            @Override
            public boolean canStep(int fx, int fy, int tx, int ty) {
                return view.canStep(fx, fy, tx, ty);
            }

            @Override
            public boolean occupied(int x, int y) {
                return false;
            }

            @Override
            public int extra(int x, int y) {
                return 0;
            }

            @Override
            public boolean drives(Route.Step place, Point pos) {
                return place == toll;
            }
        };
        assertTrue(
                follower.aim(route, falador, handsOver, WindowSearch.Momentum.NONE, null)
                        instanceof RouteFollower.Delegate,
                "wherever the view hands it over");
    }

    @Test
    @DisplayName("a committed walk into the destination is searched in the uncommitted window")
    void aCommittedArriveKeepsTheUncommittedWindow() {
        Point pos = Point.of(246, 562);
        Rect bank = Rect.of(283, 567, 283, 567);
        Route route = new Route(List.of(new Route.Arrive(bank, Inventory.empty())), 0, Map.of());
        RouteFollower follower = new RouteFollower(map);
        StepView view = StepView.ofMap(map, Planner.DangerCharge.NONE);

        RouteFollower.Walk fresh =
                (RouteFollower.Walk) follower.aim(route, pos, view, WindowSearch.Momentum.NONE, null);
        RouteFollower.Walk committed =
                (RouteFollower.Walk) follower.aim(route, pos, view, WindowSearch.Momentum.NONE, bank);
        assertEquals(fresh.corridor(), committed.corridor(), "the same window either way");

        List<Point> walk = follower.search()
                .run(
                        view,
                        pos,
                        committed.target(),
                        committed.corridor(),
                        committed.onward(),
                        true,
                        WindowSearch.Momentum.NONE);
        assertNotNull(walk);
        assertTrue(walk.get(1).y() > pos.y(), "heads south, the way the engine priced: " + walk.get(1));
        assertTrue(walk.size() <= 70, "the 66-tile walk, not the 154-tile one north: " + walk.size());
    }

    @Test
    @DisplayName("a funded route banks its fare, ships around White Wolf Mountain and arrives")
    void aBankStopIsMadeAndTheWalkGoesOnWithWhatItWithdrew() {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 1, 1, 0);
        }
        stats.setSkill(Skill.HITS.ordinal(), 10, 10, 0);
        MutableBank bank = new MutableBank();
        bank.setSlot(0, new BankSlot(0, ItemId.COINS.id(), 1000));
        AccountFacts pauper = new AccountFacts.Snapshot(
                env,
                new MutableInventory().snapshot(),
                bank.snapshot(),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                Point.of(390, 498));
        List<NavProbe.ProbeStep> walk = NavProbe.planBetween(pf, 390, 498, 404, 498, pauper, NavPolicy.DEFAULT);
        assertNotNull(walk);
        NavProbe.ProbeStep last = walk.get(walk.size() - 1);
        assertEquals("404,498", last.x() + "," + last.y(), "the walk did not arrive");
        boolean banked = false;
        List<String> hops = new ArrayList<>();
        for (NavProbe.ProbeStep s : walk) {
            banked |= s.bankVisit();
            if (s.hopName() != null) {
                hops.add(s.hopName());
            }
        }
        assertTrue(banked, "the fare is withdrawn on the way");
        assertTrue(
                hops.stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).contains("ship")),
                "ships around the mountain: " + hops);
    }

    private static int[] gridFrom(int sx, int sy) {
        int w = CollisionMap.WIDTH;
        int[] d = new int[w * CollisionMap.FLOOR_HEIGHT];
        Arrays.fill(d, -1);
        ArrayDeque<Integer> q = new ArrayDeque<>();
        d[sy * w + sx] = 0;
        q.add(sy * w + sx);
        while (!q.isEmpty()) {
            int t = q.poll();
            int x = t % w;
            int y = t / w;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int nx = x + dx;
                    int ny = y + dy;
                    if ((dx | dy) == 0 || nx < 0 || ny < 0 || nx >= w || ny >= CollisionMap.FLOOR_HEIGHT) {
                        continue;
                    }
                    int n = ny * w + nx;
                    if (d[n] < 0 && map.canStep(x, y, nx, ny)) {
                        d[n] = d[t] + 1;
                        q.add(n);
                    }
                }
            }
        }
        return d;
    }
}
