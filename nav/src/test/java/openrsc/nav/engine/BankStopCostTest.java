package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.data.Location;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.BankSite;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.transports.Banks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BankStopCostTest {

    private static final Point LUMBRIDGE = Point.of(122, 647);
    private static final NavPolicy ON_FOOT =
            new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, false, Map.of(), false);

    private static final LoadoutSpec SWORD =
            new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.BRONZE_LONG_SWORD, 1, 1, 1, false)), false, true);

    private static ServerData env;
    private static Pathfinder pf;
    private static MutableStats stats;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
        stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
    }

    private static AccountFacts banking(ItemId item, Point at) {
        MutableBank bank = new MutableBank();
        bank.setSlot(0, new BankSlot(0, item.id(), 1));
        return new AccountFacts.Snapshot(
                env,
                new MutableInventory().snapshot(),
                bank.snapshot(),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                at);
    }

    private static Route plan(AccountFacts account, Dest goal, LoadoutSpec kit, NavPolicy policy) {
        PlanResult r = pf.nav().plan(new PlanRequest(account, account.pos(), goal, kit, policy));
        return assertInstanceOf(PlanResult.Planned.class, r, "plans: " + r).route();
    }

    private static Point stopOf(Route route) {
        return route.steps().stream()
                .filter(Route.BankStop.class::isInstance)
                .map(s -> ((Route.BankStop) s).bank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bank stop: " + route.steps()));
    }

    private static Area arrivalOf(Route route) {
        return ((Route.Arrive) route.steps().get(route.steps().size() - 1)).target();
    }

    @Test
    void aPlannedStopCostsItsBanksPriceAndAPolicyRepricesItByName() {
        AccountFacts fighter = banking(ItemId.BRONZE_LONG_SWORD, LUMBRIDGE);
        BankSite draynor = Banks.of(Location.BANK_DRAYNOR);

        Route base = plan(fighter, LUMBRIDGE, SWORD, ON_FOOT);
        assertEquals(draynor.tile(), stopOf(base), "Draynor is the nearest bank");

        Route dearer = plan(fighter, LUMBRIDGE, SWORD, ON_FOOT.cost(draynor, draynor.cost() + 30));
        assertEquals(draynor.tile(), stopOf(dearer), "three ticks more still leaves Draynor nearest");
        assertEquals(base.totalCostDeci() + 30, dearer.totalCostDeci(), "the raise is the whole difference");

        Route wire = plan(fighter, LUMBRIDGE, SWORD, withOverride(ON_FOOT, draynor.name(), draynor.cost() + 30));
        assertEquals(dearer.totalCostDeci(), wire.totalCostDeci(), "a name on the wire is the same override");

        Route shunned = plan(fighter, LUMBRIDGE, SWORD, ON_FOOT.cost(draynor, 100_000));
        assertNotEquals(draynor.tile(), stopOf(shunned), "a busy enough Draynor sends the stop elsewhere");
    }

    @Test
    void aWalkToAnyBankForTheKitPaysEachBanksStop() {
        AccountFacts fighter = banking(ItemId.BRONZE_LONG_SWORD, LUMBRIDGE);
        BankSite draynor = Banks.of(Location.BANK_DRAYNOR);

        Route base = plan(fighter, Banks.any(env), SWORD, ON_FOOT);
        assertEquals(draynor.tile(), stopOf(base), "Draynor is the nearest bank");

        Route dearer = plan(fighter, Banks.any(env), SWORD, ON_FOOT.cost(draynor, draynor.cost() + 30));
        assertEquals(draynor.tile(), stopOf(dearer), "three ticks more still leaves Draynor nearest");
        assertEquals(base.totalCostDeci() + 30, dearer.totalCostDeci(), "the raise is the whole difference");

        Route shunned = plan(fighter, Banks.any(env), SWORD, ON_FOOT.cost(draynor, 100_000));
        assertNotEquals(draynor.tile(), arrivalOf(shunned), "a busy enough Draynor sends the walk to another bank");
        assertEquals(arrivalOf(shunned), stopOf(shunned), "and it banks where it arrives");
    }

    @Test
    void anEstimateThroughABankStopCostsThatBanksPrice() {
        Point inside = Point.of(202, 484);
        Point outside = Point.of(202, 486);
        AccountFacts keyless = banking(ItemId.BRASS_KEY, outside);
        int base = pf.nav().estimate(keyless, outside, inside, ON_FOOT).deci();
        NavPolicy dearer = ON_FOOT;
        for (BankSite b : Banks.on(env)) {
            dearer = dearer.cost(b, b.cost() + 30);
        }
        int raised = pf.nav().estimate(keyless, outside, inside, dearer).deci();
        MutableInventory key = new MutableInventory();
        key.setSlot(0, new InventorySlot(0, ItemId.BRASS_KEY.id(), 1, false));
        AccountFacts holder = new AccountFacts.Snapshot(
                env,
                key.snapshot(),
                new MutableBank().snapshot(),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                outside);
        int direct = pf.nav().estimate(holder, outside, inside, ON_FOOT).deci();
        assertTrue(
                direct >= 0 && base > direct, "the key in the bank is a trip to fetch it: " + base + " vs " + direct);
        assertEquals(base + 30, raised, "every stop three ticks dearer: the trip is three ticks longer");
    }

    @Test
    void aBankStopTakesAPriceNeverAProhibit() {
        BankSite draynor = Banks.of(Location.BANK_DRAYNOR);
        assertThrows(IllegalArgumentException.class, () -> ON_FOOT.cost(draynor, -1));
        assertThrows(IllegalArgumentException.class, () -> ON_FOOT.cost(draynor, NavPolicy.PROHIBITIVE));
        assertThrows(IllegalArgumentException.class, () -> ON_FOOT.cost(draynor, BankSite.MAX_COST + 1));
        AccountFacts fighter = banking(ItemId.BRONZE_LONG_SWORD, LUMBRIDGE);
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(fighter, LUMBRIDGE, SWORD, withOverride(ON_FOOT, draynor.name(), NavPolicy.PROHIBITIVE)));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(fighter, LUMBRIDGE, SWORD, withOverride(ON_FOOT, draynor.name(), Integer.MAX_VALUE - 1)));
        assertThrows(
                IllegalArgumentException.class,
                () -> plan(fighter, LUMBRIDGE, SWORD, withOverride(ON_FOOT, "Draynor bnak", 200)));
    }

    private static NavPolicy withOverride(NavPolicy policy, String key, int value) {
        Map<String, Integer> overrides = new HashMap<>(policy.costOverrides());
        overrides.put(key, value);
        return new NavPolicy(
                policy.danger(),
                policy.teleportSpells(),
                policy.teleportJewellery(),
                Map.copyOf(overrides),
                policy.assumeAllEligible());
    }
}
