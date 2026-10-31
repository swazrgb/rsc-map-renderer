package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.TracedRoute;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TracerTest {

    private static final NavPolicy DANGER_OFF = NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF);

    private static Pathfinder pf;
    private static CollisionMap map;

    @BeforeAll
    static void load() {
        pf = RealDefs.pathfinder();
        map = pf.serverData().collisionMap();
    }

    @Test
    @DisplayName("a walk-only route traces to one unbroken walk from its start into its goal")
    void aWalkTracesUnbroken() {
        Point from = Point.of(71, 323);
        Point goal = Point.of(61, 615);
        Route route = plan(NavProbe.onFootFacts(pf.serverData(), from.x(), from.y()), from, goal, DANGER_OFF);
        TracedRoute t = pf.nav().trace(from, route);
        assertNotNull(t, "the engine traces");

        assertEquals(from, at(t, 0), "starts where the route was planned from");
        assertEquals(goal, at(t, t.size() - 1), "ends in the goal");
        for (int i = 1; i < t.size(); i++) {
            assertFalse(t.jump()[i], "a walk has no jumps: " + at(t, i));
            assertStep(t, i);
        }
        // Each stretch is the shortest to its own seam, not to the goal, so the whole may run a little long.
        int steps = t.size() - 1;
        assertTrue(steps >= 323 && steps <= 340, "about the shortest walk: " + steps + " steps");
    }

    @Test
    @DisplayName("a hop breaks the trace at its landing, and only there")
    void aHopBreaksAtItsLanding() {
        Point from = Point.of(122, 647);
        Point goal = Point.of(550, 600);
        AccountFacts all = NavProbe.allEligibleFacts(pf, from.x(), from.y());
        Route route = plan(all, from, goal, NavProbe.policyFor(null, DANGER_OFF));
        Set<Point> landings = new HashSet<>();
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.Hop h) {
                landings.add(h.landing());
            }
        }
        assertFalse(landings.isEmpty(), "the premise: the route takes a transport");
        TracedRoute t = pf.nav().trace(from, route);
        assertNotNull(t, "the engine traces");

        assertEquals(goal, at(t, t.size() - 1), "ends in the goal");
        for (int i = 1; i < t.size(); i++) {
            if (t.jump()[i]) {
                assertTrue(landings.contains(at(t, i)), "only a landing jumps: " + at(t, i));
            } else {
                assertStep(t, i);
            }
        }
    }

    @Test
    @DisplayName("a stretch on open ground traces the straight line the walker walks, not a staircase")
    void openGroundTracesStraight() {
        Point from = Point.of(635, 476);
        Point bend = Point.of(650, 449);
        Point goal = Point.of(664, 3293);
        Route route = plan(
                NavProbe.allEligibleFacts(pf, from.x(), from.y()), from, goal, NavProbe.policyFor(null, DANGER_OFF));
        TracedRoute t = pf.nav().trace(from, route);
        assertNotNull(t, "the engine traces");
        int at = -1;
        for (int i = 0; i < t.size() && at < 0; i++) {
            if (at(t, i).equals(bend)) {
                at = i;
            }
        }
        assertTrue(at > 0, "the premise: the trace passes " + bend);
        for (int i = 0; i <= at; i++) {
            double off = offLine(at(t, i), from, bend);
            assertTrue(off <= 1.0, "tile " + i + " " + at(t, i) + " is " + off + " tiles off the straight line");
        }
    }

    private static double offLine(Point p, Point a, Point b) {
        double dx = b.x() - a.x();
        double dy = b.y() - a.y();
        double cross = Math.abs(dx * (p.y() - a.y()) - dy * (p.x() - a.x()));
        return cross / Math.hypot(dx, dy);
    }

    private static Route plan(AccountFacts facts, Point from, Point goal, NavPolicy policy) {
        PlanResult r = pf.nav().plan(new PlanRequest(facts, from, goal, LoadoutSpec.any(), policy));
        return assertInstanceOf(PlanResult.Planned.class, r, "plans").route();
    }

    private static void assertStep(TracedRoute t, int i) {
        Point a = at(t, i - 1);
        Point b = at(t, i);
        assertTrue(
                ClusterEdges.canStep(map, a.x(), a.y(), b.x(), b.y()),
                "every walked tile is one legal step from the last: " + a + " -> " + b);
    }

    private static Point at(TracedRoute t, int i) {
        return Point.of(t.xs()[i], t.ys()[i]);
    }
}
