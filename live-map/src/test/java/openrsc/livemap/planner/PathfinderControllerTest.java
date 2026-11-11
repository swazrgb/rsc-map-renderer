package openrsc.livemap.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import openrsc.livemap.assets.MapNav;
import openrsc.nav.NavPolicy;
import openrsc.nav.RealDefs;
import openrsc.nav.planner.PathfinderPlans;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

class PathfinderControllerTest {

    private static final PathfinderPlans.Tile FROM = new PathfinderPlans.Tile(122, 647);
    private static final PathfinderPlans.Tile TO = new PathfinderPlans.Tile(126, 650);

    private static PathfinderController controller(Map<String, PlannerAccounts> accounts) {
        return new PathfinderController(
                MapNav.of(RealDefs.pathfinder()),
                new StaticListableBeanFactory(Map.copyOf(accounts)).getBeanProvider(PlannerAccounts.class));
    }

    private static PathfinderPlans.Request request(String as, NavPolicy policy) {
        return new PathfinderPlans.Request(FROM, TO, as, null, null, policy);
    }

    @Test
    void plansAsANamedAccountOfTheApplications() {
        PlannerAccounts known = name -> name.equals("alice")
                ? NavProbe.onFootFacts(RealDefs.pathfinder().serverData(), 122, 647)
                : null;
        PathfinderController c = controller(Map.of("accounts", known));

        var plan = c.plan(request("alice", null));
        assertEquals(200, plan.getStatusCode().value());
        assertNotNull(plan.getBody());
        assertTrue(plan.getBody().found(), "a walk across Lumbridge");

        assertEquals(404, c.plan(request("bob", null)).getStatusCode().value(), "an account it does not know");
        assertEquals(200, c.plan(request(null, null)).getStatusCode().value(), "an account described by hand");
    }

    @Test
    void withoutAccountsOfItsOwnANamedAccountIsUnknown() {
        assertEquals(
                404,
                controller(Map.of())
                        .plan(request("alice", null))
                        .getStatusCode()
                        .value());
    }

    @Test
    void aRequestThePlannerCannotReadIsRefused() {
        NavPolicy d = NavPolicy.DEFAULT;
        NavPolicy unknownTransport = new NavPolicy(
                d.danger(),
                d.teleportSpells(),
                d.teleportJewellery(),
                Map.of("no such transport", 10),
                d.assumeAllEligible(),
                d.avoidSkulled(),
                d.berth());
        assertEquals(
                400,
                controller(Map.of())
                        .plan(request(null, unknownTransport))
                        .getStatusCode()
                        .value());
    }
}
