package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.cache.NavCaches;
import openrsc.nav.planner.InputsProbeBot;
import org.junit.jupiter.api.Test;

class ScotRuthTunnelBuyTest {

    private static final Point EDGEVILLE = Point.of(217, 450);

    private static final Point CHAOS_ALTAR = Point.of(331, 215);

    private static Pathfinder pathfinder;

    @Test
    void aTripTheTunnelSpeedsBuysItFromTheBank() {
        Route route = plan(250_000, false);
        assertTrue(
                uses(route, SpecialCrossings.SCOT_RUTH_TUNNEL_BUY.name()), "walked past the tunnel: " + route.steps());
    }

    @Test
    void aStrictLoadoutWithdrawsThePriceBeforeTheBuy() {
        LoadoutSpec noCoins = LoadoutSpec.of(new LoadoutSpec.Item(ItemId.BRONZE_AXE, 0, 0, 1, false));
        Route route = plan(ScotRuthTunnelTransport.PRICE, false, noCoins);
        List<Route.Step> steps = route.steps();
        int buy = indexOf(steps, SpecialCrossings.SCOT_RUTH_TUNNEL_BUY.name());
        assertTrue(buy >= 0, "walked past the tunnel: " + steps);
        Route.BankStop stop = null;
        for (int i = 0; i < buy; i++) {
            if (steps.get(i) instanceof Route.BankStop b) {
                stop = b;
            }
        }
        assertTrue(stop != null, "no bank stop before the buy: " + steps);
        assertTrue(
                Point.chebyshev(stop.bank().x(), stop.bank().y(), EDGEVILLE.x(), EDGEVILLE.y()) <= 10,
                "funded away from the Edgeville bank: " + stop.bank());
        assertEquals(
                (long) ScotRuthTunnelTransport.PRICE,
                stop.manifest().withdraw().get(ItemId.COINS),
                "the stop before the buy: " + stop.manifest());
    }

    @Test
    void tooFewCoinsWalks() {
        Route route = plan(ScotRuthTunnelTransport.PRICE - 1, false);
        assertFalse(uses(route, SpecialCrossings.SCOT_RUTH_TUNNEL_BUY.name()), "bought it short: " + route.steps());
        assertFalse(
                uses(route, SceneryCrossings.SCOT_RUTH_TUNNEL_CHAOS_ALTAR.name()), "unbought tunnel: " + route.steps());
    }

    @Test
    void aBoughtTunnelIsTheFreeRow() {
        Route route = plan(250_000, true);
        assertTrue(
                uses(route, SceneryCrossings.SCOT_RUTH_TUNNEL_CHAOS_ALTAR.name()),
                "the bought tunnel went unused: " + route.steps());
        assertFalse(uses(route, SpecialCrossings.SCOT_RUTH_TUNNEL_BUY.name()), "bought it twice: " + route.steps());
    }

    private static Route plan(int bankedCoins, boolean bought) {
        return plan(bankedCoins, bought, LoadoutSpec.any());
    }

    private static Route plan(int bankedCoins, boolean bought, LoadoutSpec loadout) {
        // The tunnel exists only on a world with the custom quests.
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        AccountFacts account = new InputsProbeBot(
                coleslaw,
                Map.of(),
                Map.of(),
                Set.of(),
                Map.of(ItemId.COINS.id(), bankedCoins),
                Map.of(),
                new CacheValues(Map.of(ScotRuthTunnelTransport.KEY, bought), Map.of()));
        PlanResult r = pathfinder(coleslaw)
                .nav()
                .plan(new PlanRequest(account, EDGEVILLE, CHAOS_ALTAR, loadout, NavPolicy.DEFAULT));
        return assertInstanceOf(PlanResult.Planned.class, r).route();
    }

    private static boolean uses(Route route, String transport) {
        return indexOf(route.steps(), transport) >= 0;
    }

    private static int indexOf(List<Route.Step> steps, String transport) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Route.Hop h && h.transport().startsWith(transport)) {
                return i;
            }
        }
        return -1;
    }

    private static synchronized Pathfinder pathfinder(ServerData coleslaw) {
        if (pathfinder == null) {
            pathfinder = Pathfinder.load(coleslaw, NavCaches.none());
        }
        return pathfinder;
    }
}
