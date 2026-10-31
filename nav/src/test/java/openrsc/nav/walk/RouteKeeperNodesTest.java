package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.MutableStats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RouteKeeperNodesTest {

    private static final Point EAST = Point.of(96, 649);
    private static final Point ENTRY = Point.of(92, 649);

    // Where the border guard puts a player who has finished Prince Ali Rescue: two tiles, through the
    // fence.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/alkharid/BorderGuard.java#L27
    private static final Point LANDING = Point.of(90, 649);
    private static final Point WEST = Point.of(85, 668);

    private static ServerData env;
    private static Pathfinder pf;
    private static AccountFacts account;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
        MutableQuests quests = new MutableQuests();
        quests.setStage(QuestId.PRINCE_ALI_RESCUE.id(), -1);
        // Built once: a trip key compares the inventory by identity, so a new snapshot is a new trip.
        account = new AccountFacts.Snapshot(
                env, Inventory.empty(), Bank.empty(), new MutableStats().snapshot(), quests.snapshot(), EAST);
    }

    @Test
    @DisplayName("the gate's landing finishes the hop: the route carries on instead of walking back")
    void aCrossedHopIsPopped() {
        RouteKeeper keeper = new RouteKeeper(pf.nav());
        Route east = route(keeper, EAST);
        Route.Hop gate = assertInstanceOf(
                Route.Hop.class, place(east), "the premise: from " + EAST + " the trip takes a transport first");
        assertEquals(ENTRY, gate.entry(), "and it is the gate, entered from the east");
        assertEquals(LANDING, gate.landing(), "landing where the guard puts us");

        route(keeper, ENTRY);
        int plans = keeper.plans();

        Route crossed = route(keeper, LANDING);
        assertEquals(plans, keeper.plans(), "a finished node needs no new plan");
        assertTrue(
                place(crossed) instanceof Route.Arrive || place(crossed) instanceof Route.BankStop,
                "the gate is behind us: " + place(crossed));
        for (Route.Step s : crossed.steps()) {
            assertTrue(
                    !(s instanceof Route.Hop h && h.entry().equals(ENTRY)),
                    "the route no longer names the crossing we just made");
        }
    }

    @Test
    @DisplayName("moved two tiles to somewhere the route did not plan for: plan again")
    void aRelocationThatFinishesNothingPlansAgain() {
        RouteKeeper keeper = new RouteKeeper(pf.nav());
        route(keeper, EAST);
        route(keeper, ENTRY);
        int plans = keeper.plans();
        Route elsewhere = route(keeper, Point.of(90, 648));
        assertEquals(plans + 1, keeper.plans(), "the walk to the gate grew by more than a tick's walk");
        assertTrue(
                place(elsewhere) instanceof Route.Arrive || place(elsewhere) instanceof Route.BankStop,
                "and the fresh plan walks on from the west side: " + place(elsewhere));
    }

    @Test
    @DisplayName("a walk that never gets closer is asked again, however it got stuck")
    void aStalledWalkPlansAgain() {
        RouteKeeper keeper = new RouteKeeper(pf.nav());
        route(keeper, EAST);
        int plans = keeper.plans();
        for (int i = 0; i < 60; i++) {
            route(keeper, EAST);
        }
        assertEquals(plans + 1, keeper.plans(), "one fresh plan after the stall, not one a tick and not none");
    }

    @Test
    @DisplayName("a walk that is getting there is kept, tile after tile")
    void aWalkThatProgressesIsKept() {
        RouteKeeper keeper = new RouteKeeper(pf.nav());
        route(keeper, Point.of(99, 649));
        int plans = keeper.plans();
        for (int x = 98; x >= 92; x--) {
            route(keeper, Point.of(x, 649));
        }
        assertEquals(plans, keeper.plans(), "no re-plan while the walk closes on the gate");
    }

    private static Route route(RouteKeeper keeper, Point pos) {
        PlanRequest ask =
                new PlanRequest(account.at(pos.x(), pos.y()), pos, WEST, LoadoutSpec.any(), NavPolicy.DEFAULT);
        return assertInstanceOf(PlanResult.Planned.class, keeper.plan(ask), "plans from " + pos)
                .route();
    }

    private static Route.Step place(Route route) {
        List<Route.Step> steps = route.steps();
        for (Route.Step s : steps) {
            if (!(s instanceof Route.Cross)) {
                return s;
            }
        }
        throw new AssertionError("a route ends in its arrival: " + steps);
    }
}
