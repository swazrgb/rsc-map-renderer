package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RouteKeeperTest {

    private static final Point FROM = Point.of(71, 323);
    private static final Point GOAL = Point.of(61, 615);
    private static final NavPolicy DANGER_OFF = NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF);

    private static ServerData env;
    private static Pathfinder pf;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
    }

    @Test
    @DisplayName("with danger off the walk is the plain one — the shortest there is")
    void withDangerOffTheWalkIsThePlainOne() {
        List<NavProbe.ProbeStep> walk = walk(FROM, GOAL, DANGER_OFF);
        NavProbe.ProbeStep last = walk.get(walk.size() - 1);
        assertEquals(GOAL, Point.of(last.x(), last.y()), "arrives");
        // The grid's shortest walk between FROM and GOAL.
        assertEquals(323, walk.size() - 1, "walks the shortest way");
    }

    @Test
    @DisplayName("beyond what the bot can see the walk hugs the fields the plan priced round")
    void beyondViewTheWalkFollowsTheField() {
        List<NavProbe.ProbeStep> plain = walk(FROM, GOAL, DANGER_OFF);
        List<NavProbe.ProbeStep> guided = walk(FROM, GOAL, NavPolicy.DEFAULT);
        NavProbe.ProbeStep last = guided.get(guided.size() - 1);
        assertEquals(GOAL, Point.of(last.x(), last.y()), "arrives");
        int plainCharged = charged(plain, NavPolicy.DEFAULT);
        int guidedCharged = charged(guided, NavPolicy.DEFAULT);
        assertTrue(plainCharged > 0, "the premise: the plain walk crosses charged ground");
        assertTrue(
                guidedCharged <= plainCharged,
                "the guided walk crosses no more of it: " + guidedCharged + " charged tiles against the plain walk's "
                        + plainCharged);
        assertTrue(
                guided.size() >= plain.size(),
                "and is never quicker than the plain walk: " + guided.size() + " against " + plain.size());
    }

    @Test
    @DisplayName("within view the walk is the plain one, charged ground or not")
    void withinViewTheWalkIsThePlainOne() {
        Point a = Point.of(211, 400);
        Point b = Point.of(211, 414);
        assertTrue(
                Point.pythagoras(a.x(), a.y(), b.x(), b.y()) <= env.world().mobRange(),
                "the premise: the whole trip is in view");
        List<NavProbe.ProbeStep> plain = walk(a, b, DANGER_OFF);
        List<NavProbe.ProbeStep> guided = walk(a, b, NavPolicy.DEFAULT);
        assertTrue(charged(plain, NavPolicy.DEFAULT) > 0, "the premise: the strip is charged");
        assertEquals(tiles(plain), tiles(guided), "the same plain walk under both policies");
    }

    @Test
    @DisplayName("a walked route is kept, and its duration keeps falling as it is walked")
    void aWalkedRouteIsKept() {
        List<NavProbe.ProbeStep> walk = walk(FROM, GOAL, NavPolicy.DEFAULT);
        Planner real = pf.nav();
        RouteKeeper keeper = new RouteKeeper(real);
        AccountFacts account = NavProbe.onFootFacts(env, FROM.x(), FROM.y());
        Object lastKey = null;
        int best = Integer.MAX_VALUE;
        int sinceBest = 0;
        int keyChanges = 0;
        for (NavProbe.ProbeStep s : walk) {
            Point p = Point.of(s.x(), s.y());
            PlanRequest ask = new PlanRequest(account.at(p.x(), p.y()), p, GOAL, LoadoutSpec.any(), NavPolicy.DEFAULT);
            Object key = real.tripKey(ask);
            PlanResult r = keeper.plan(ask);
            int cost = assertInstanceOf(PlanResult.Planned.class, r, "plans from " + p)
                    .route()
                    .totalCostDeci();
            if (!key.equals(lastKey)) {
                if (lastKey != null) {
                    keyChanges++;
                }
                best = cost;
                sinceBest = 0;
            } else if (cost < best) {
                best = cost;
                sinceBest = 0;
            } else {
                assertTrue(
                        ++sinceBest < 20,
                        "no progress for " + sinceBest + " ticks at " + p + ", duration " + cost + ", best " + best);
            }
            lastKey = key;
        }
        assertTrue(
                keeper.plans() <= keyChanges + 2,
                keeper.plans() + " full plans for " + walk.size() + " ticks and " + keyChanges + " input changes ("
                        + keeper.leads() + " leads along the field)");
    }

    private static List<NavProbe.ProbeStep> walk(Point from, Point goal, NavPolicy policy) {
        AccountFacts account = NavProbe.onFootFacts(env, from.x(), from.y());
        return NavProbe.planBetween(
                pf,
                from.x(),
                from.y(),
                goal.x(),
                goal.y(),
                account,
                policy,
                LoadoutSpec.any(),
                new NavProbe.SimulationStats());
    }

    private static int charged(List<NavProbe.ProbeStep> walk, NavPolicy policy) {
        AccountFacts account =
                NavProbe.onFootFacts(env, walk.get(0).x(), walk.get(0).y());
        int n = 0;
        for (NavProbe.ProbeStep s : walk) {
            if (pf.nav().dangerAt(account, policy, s.x(), s.y()) > 0) {
                n++;
            }
        }
        return n;
    }

    private static List<Point> tiles(List<NavProbe.ProbeStep> walk) {
        List<Point> out = new ArrayList<>(walk.size());
        for (NavProbe.ProbeStep s : walk) {
            out.add(Point.of(s.x(), s.y()));
        }
        return out;
    }
}
