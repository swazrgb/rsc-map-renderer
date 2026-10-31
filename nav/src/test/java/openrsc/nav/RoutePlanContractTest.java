package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.engine.Engine;
import org.junit.jupiter.api.Test;

/**
 * Every route the planner makes over seeded requests walks as it projects: each hop open to its pack,
 * each fare paid, each stop within the pack and the bank, the arrival within the loadout. The engine
 * checks that itself on every plan under assertions, so a broken route is a plan that throws.
 */
class RoutePlanContractTest {

    private static final int CASES = 1500;

    @Test
    void uranium_routes_walk_as_planned() {
        holds(RealDefs.serverData(), RealDefs.pathfinder(), 1);
    }

    @Test
    void coleslaw_routes_walk_as_planned() {
        holds(RealDefs.serverData(RealDefs.COLESLAW), RealDefs.pathfinder(RealDefs.COLESLAW), 2);
    }

    private static void holds(ServerData defs, Pathfinder pf, long salt) {
        assertTrue(Engine.class.desiredAssertionStatus(), "the engine checks its routes under assertions");
        PlanCases cases = new PlanCases(defs, pf);
        List<String> broken = new ArrayList<>();
        int planned = 0;
        for (int i = 0; i < CASES; i++) {
            PlanRequest request;
            try {
                request = cases.request(salt * 1_000_003L + i);
            } catch (IllegalArgumentException overfull) {
                continue;
            }
            if (request == null) {
                continue;
            }
            try {
                if (pf.nav().plan(request) instanceof PlanResult.Planned) {
                    planned++;
                }
            } catch (IllegalStateException e) {
                broken.add("case " + i + ": " + e.getMessage());
            }
        }
        assertTrue(broken.isEmpty(), broken.size() + " broken, e.g. " + (broken.isEmpty() ? "" : broken.getFirst()));
        assertTrue(planned > CASES / 4, "the premise: most cases plan a route, " + planned + " did");
    }
}
