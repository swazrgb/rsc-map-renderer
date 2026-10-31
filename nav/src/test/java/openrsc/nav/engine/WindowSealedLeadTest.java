package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowSealedLeadTest {

    private static final Point FROM = Point.of(110, 465);
    private static final Rect TO = Rect.of(100, 312, 100, 312);

    @Test
    @DisplayName("a lead sealed in by danger names the plan's corridor")
    void aSealedLeadNamesThePlansCorridor() {
        ServerData sd = RealDefs.serverData();
        Planner nav = RealDefs.pathfinder().nav();
        AccountFacts facts = NavProbe.onFootFacts(sd, FROM.x(), FROM.y());
        NavPolicy policy = NavProbe.policyFor(facts, NavPolicy.DEFAULT);

        PlanResult plan = nav.plan(new PlanRequest(facts, FROM, TO, LoadoutSpec.any(), policy));
        List<Rect> corridor = assertInstanceOf(PlanResult.Planned.class, plan).route().steps().stream()
                .filter(Route.Cross.class::isInstance)
                .map(c -> ((Route.Cross) c).span())
                .toList();
        assertFalse(corridor.isEmpty(), "the plan detours through seams: " + plan);

        Planner.Leg lead = nav.walk(FROM, TO, facts, policy);
        assertEquals(corridor, lead == null ? null : lead.crossings(), "lead " + lead);
    }

    @Test
    @DisplayName("walked as a bot walks it, the trip never enters the Giant's roam box")
    void theWalkStaysOutOfTheGiantsBox() {
        ServerData sd = RealDefs.serverData();
        AccountFacts facts = NavProbe.onFootFacts(sd, FROM.x(), FROM.y());
        NavPolicy policy = NavProbe.policyFor(facts, NavPolicy.DEFAULT);
        // NPC 61's roam box, spawned at (80, 329):
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/conf/server/defs/locs/NpcLocs.json#L2971
        Rect giantBox = Rect.of(75, 324, 85, 334);

        List<NavProbe.ProbeStep> walk =
                NavProbe.planBetween(RealDefs.pathfinder(), FROM.x(), FROM.y(), TO.minX(), TO.minY(), facts, policy);
        NavProbe.ProbeStep last = walk.getLast();
        assertEquals(Point.of(TO.minX(), TO.minY()), Point.of(last.x(), last.y()), "the walk arrives");
        for (NavProbe.ProbeStep s : walk) {
            assertFalse(giantBox.contains(s.x(), s.y()), "walked into the Giant's box at " + s.x() + "," + s.y());
        }
    }

    @Test
    @DisplayName("a lead the field does not touch stays the window's own walk")
    void anUntouchedLeadStaysLocal() {
        ServerData sd = RealDefs.serverData();
        Planner nav = RealDefs.pathfinder().nav();
        AccountFacts facts = NavProbe.onFootFacts(sd, 120, 650);
        NavPolicy policy = NavProbe.policyFor(facts, NavPolicy.DEFAULT);

        Planner.Leg lead = nav.walk(Point.of(120, 650), Rect.of(132, 660, 132, 660), facts, policy);
        assertEquals(List.of(), lead == null ? null : lead.crossings(), "lead " + lead);
    }
}
