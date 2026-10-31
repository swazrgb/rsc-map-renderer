package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowEdgeWalkTest {

    private static final Point GOAL = Point.of(254, 426);

    @Test
    @DisplayName("a walk-only route that leaves the local square is planned as that route")
    void theLightWayOutOfTheSquareIsTheRoute() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        for (Point from : List.of(Point.of(302, 416), Point.of(303, 417))) {
            AccountFacts facts = NavProbe.onFootFacts(env, from.x(), from.y());
            PlanResult r = pf.nav().plan(new PlanRequest(facts, from, GOAL, LoadoutSpec.any(), NavPolicy.DEFAULT));
            Route route = assertInstanceOf(PlanResult.Planned.class, r, "plans from " + from)
                    .route();
            assertTrue(
                    route.steps().get(0) instanceof Route.Cross,
                    "the walker is handed the corridor from " + from + ": " + route.steps());
            assertEquals(
                    route.totalCostDeci(),
                    pf.nav().estimate(facts, from, GOAL, NavPolicy.DEFAULT).deci(),
                    "plan and estimate agree from " + from);
        }
        AccountFacts edge = NavProbe.onFootFacts(env, 302, 416);
        assertEquals(
                1150,
                pf.nav()
                        .estimate(edge, Point.of(302, 416), GOAL, NavPolicy.DEFAULT)
                        .deci(),
                "the duration reported is that walk's, priced exactly");
    }

    @Test
    @DisplayName("the walker, planning every tick, arrives instead of trading two tiles")
    void theWalkerArrives() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        AccountFacts facts = NavProbe.onFootFacts(env, 259, 314);
        List<NavProbe.ProbeStep> walk = NavProbe.planBetween(
                pf,
                259,
                314,
                GOAL.x(),
                GOAL.y(),
                facts,
                NavPolicy.DEFAULT,
                LoadoutSpec.any(),
                new NavProbe.SimulationStats());
        assertNotNull(walk);
        NavProbe.ProbeStep last = walk.get(walk.size() - 1);
        assertEquals(GOAL, Point.of(last.x(), last.y()), "arrives; walked " + walk.size() + " steps");
        assertTrue(walk.size() < 300, "without the back-and-forth: " + walk.size() + " steps");
    }
}
