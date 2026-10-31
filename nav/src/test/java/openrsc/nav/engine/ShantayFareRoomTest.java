package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.RouteContext;
import org.junit.jupiter.api.Test;

/**
 * The pass is bought from a shop, which caps a buy of an item that does not stack at the free slots
 * before it takes the coins: the buy needs a slot free beside the coins that pay for it.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/InterfaceShopHandler.java#L139">InterfaceShopHandler.buyShopItem</a>
 */
class ShantayFareRoomTest {

    private static final Point NORTH = Point.of(62, 727);
    private static final Rect DESERT = Rect.of(64, 760, 64, 760);

    private static final int COINS = ItemId.COINS.id();
    private static final int PASS = ItemId.SHANTAY_DESERT_PASS.id();
    private static final int LOGS = ItemId.LOGS.id();

    @Test
    void a_stop_draws_the_exact_fare_into_a_free_slot_and_the_trip_arrives_with_it_free() {
        Route route = planned(account(28, 0, false), logs(28));
        Route.Hop buy = buy(route);
        assertNotNull(buy, "the buy arm: " + route);
        assertEquals(Map.of(COINS, 6L), buy.fare(), route.toString());
        assertEquals(6, buy.pack().count(COINS), route.toString());
        assertEquals(1, buy.pack().emptySlots(), "a slot for the pass beside the coins: " + route);
        Route.BankStop stop = route.steps().stream()
                .filter(Route.BankStop.class::isInstance)
                .map(Route.BankStop.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(6, stop.leaving().count(COINS) - stop.pack().count(COINS), "drawn: " + route);
        Route.Arrive arrive = arrival(route);
        assertEquals(28, arrive.pack().count(LOGS), route.toString());
        assertEquals(0, arrive.pack().count(COINS), route.toString());
        assertEquals(2, arrive.pack().emptySlots(), route.toString());
    }

    @Test
    void twenty_nine_named_with_the_coins_in_the_bank_cannot_hold_coins_and_pass() {
        assertInstanceOf(PlanResult.NoRoute.class, plan(account(29, 0, false), logs(29)));
    }

    @Test
    void a_full_pack_of_unnamed_items_is_banked_down_to_make_room() {
        Route route = planned(account(30, 0, false), logs(5));
        Route.Hop buy = buy(route);
        assertNotNull(buy, route.toString());
        assertTrue(buy.pack().emptySlots() >= 1, route.toString());
        assertEquals(5, arrival(route).pack().count(LOGS), route.toString());
    }

    @Test
    void a_loadout_that_fills_the_pack_has_no_room_for_the_pass() {
        assertInstanceOf(PlanResult.NoRoute.class, plan(account(30, 0, false), logs(30)));
    }

    @Test
    void with_the_coins_named_the_pass_needs_one_slot_beyond_them() {
        Route route = planned(account(28, 100, false), logsAndCoins(28));
        assertNotNull(buy(route), route.toString());
        assertEquals(94, arrival(route).pack().count(COINS), route.toString());
        assertEquals(1, arrival(route).pack().emptySlots(), route.toString());

        assertInstanceOf(PlanResult.NoRoute.class, plan(account(29, 100, false), logsAndCoins(29)));
    }

    @Test
    void a_pass_aboard_is_no_fare() {
        Route route = planned(account(28, 0, true), logs(28));
        Route.Hop buy = buy(route);
        assertNotNull(buy, "the crossing, on coins: " + route);
        assertEquals(Map.of(COINS, 6L), buy.fare(), route.toString());
        assertTrue(
                route.steps().stream()
                        .noneMatch(s -> s instanceof Route.Hop h && h.fare().containsKey(PASS)),
                "nothing spends the pass aboard: " + route);
    }

    @Test
    void the_crossing_is_one_arm_funded_from_coins() {
        List<openrsc.nav.Transport> arms =
                openrsc.nav.transports.ShantayPassTransport.forWorld(RealDefs.serverData(), "SHANTAY_PASS_INTO_DESERT");
        assertEquals(1, arms.size(), arms.toString());
        for (Fact f : arms.get(0).prereq().facts()) {
            assertFalse(f.itemId() == PASS && f.kind() != Fact.Kind.ROOM_FOR, "no gate reads a carried pass: " + f);
        }
    }

    @Test
    void room_for_reads_the_pack_as_the_server_does() {
        AccountFacts full = account(29, 100, false);
        AccountFacts roomy = account(28, 100, false);
        assertTrue(RealDefs.serverData().item(PASS).stackable());
        assertFalse(Fact.roomFor(PASS, 1).test(ctx(full)));
        assertTrue(Fact.roomFor(COINS, 1000).test(ctx(full)), "merges into the stack");
        assertTrue(Fact.roomFor(PASS, 1).test(ctx(roomy)));
        assertFalse(Fact.roomFor(LOGS, 2).test(ctx(roomy)), "a non-stackable, one slot each");
        assertTrue(Fact.roomFor(PASS, 1).testBanked(ctx(full)), "a stop can bank what is in the way");
        assertTrue(Fact.roomFor(PASS, 1).readsPack());
    }

    @Test
    void the_displacement_makes_the_passes_slot_as_well_as_the_coins() {
        Route route = planned(account(30, 0, false), LoadoutSpec.any());
        Route.Hop buy = buy(route);
        assertNotNull(buy, route.toString());
        assertEquals(6, buy.pack().count(COINS), route.toString());
        assertEquals(1, buy.pack().emptySlots(), route.toString());
        assertEquals(28, arrival(route).pack().count(LOGS), route.toString());
    }

    @Test
    void a_full_pack_carrying_its_coins_banks_its_surplus_then_buys() {
        LoadoutSpec loadout = new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.LOGS, 28, 28, 29, false),
                        new LoadoutSpec.Item(ItemId.COINS, 0, 100, 1000, false)),
                false,
                false);
        Route route = planned(account(29, 100, false), loadout);
        Route.Hop buy = buy(route);
        assertNotNull(buy, "the buy arm: " + route);
        Route.BankStop stop = route.steps().stream()
                .filter(Route.BankStop.class::isInstance)
                .map(Route.BankStop.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("a stop makes the room: " + route));
        assertTrue(route.steps().indexOf(stop) < route.steps().indexOf(buy), route.toString());
        assertEquals(28, stop.leaving().count(LOGS), "the surplus log banked: " + route);
        assertEquals(106, stop.leaving().count(COINS), "the coins' target held past the fare: " + route);
        assertEquals(1, buy.pack().emptySlots(), route.toString());
        assertEquals(100, arrival(route).pack().count(COINS), route.toString());
    }

    @Test
    void a_full_any_pack_banks_an_unnamed_slot_for_the_pass() {
        Route route = planned(account(29, 100, false), LoadoutSpec.any());
        Route.Hop buy = buy(route);
        assertNotNull(buy, "the buy arm: " + route);
        assertEquals(1, buy.pack().emptySlots(), route.toString());
        assertEquals(100, buy.pack().count(COINS), "the coins carried stay: " + route);
        assertEquals(28, arrival(route).pack().count(LOGS), "one log banked: " + route);
    }

    @Test
    void a_withdraw_stop_makes_its_room_out_of_what_the_loadout_does_not_need() {
        ServerData env = RealDefs.serverData();
        assertTrue(env.item(ItemId.GOLD_BAR.id()).basePrice() > env.item(LOGS).basePrice());
        List<InventorySlot> slots = new ArrayList<>();
        for (int i = 0; i < 27; i++) {
            slots.add(new InventorySlot(slots.size(), LOGS, 1, false));
        }
        for (int i = 0; i < 3; i++) {
            slots.add(new InventorySlot(slots.size(), ItemId.GOLD_BAR.id(), 1, false));
        }
        Route route = planned(account(slots), logs(27));
        Route.Hop buy = buy(route);
        assertNotNull(buy, "the buy arm: " + route);
        assertEquals(6, buy.pack().count(COINS), route.toString());
        assertEquals(27, buy.pack().count(LOGS), route.toString());
        assertTrue(buy.pack().emptySlots() >= 1, route.toString());
        assertEquals(27, arrival(route).pack().count(LOGS), route.toString());
        assertEquals(0, arrival(route).pack().count(ItemId.GOLD_BAR.id()), route.toString());
    }

    @Test
    void a_target_gives_way_to_the_pass_and_a_min_does_not() {
        assertInstanceOf(PlanResult.NoRoute.class, plan(account(29, 100, false), logsBand(29, 29, 29)));
        Route given = planned(account(29, 100, false), logsBand(20, 29, 29));
        assertEquals(1, buy(given).pack().emptySlots(), given.toString());
        assertEquals(28, arrival(given).pack().count(LOGS), "the target gave a log: " + given);

        Route route = planned(account(29, 100, false), logsBand(20, 28, 29));
        assertNotNull(buy(route), route.toString());
        assertEquals(28, arrival(route).pack().count(LOGS), "the one above the target banked: " + route);
    }

    @Test
    void a_worn_slot_above_its_target_goes_with_the_stops_settle() {
        int amulet = ItemId.AMULET_OF_ACCURACY.id();
        LoadoutSpec loadout = new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.LOGS, 28, 28, 28, false),
                        new LoadoutSpec.Item(ItemId.COINS, 0, 100, 1000, false),
                        new LoadoutSpec.Item(ItemId.AMULET_OF_ACCURACY, 0, 0, 1, false)),
                false,
                false);
        for (boolean worn : List.of(true, false)) {
            Route route = planned(account(withAmulet(worn)), loadout);
            assertNotNull(buy(route), "worn " + worn + ": " + route);
            assertEquals(0, arrival(route).pack().count(amulet), "worn " + worn + ", banked: " + route);
            assertEquals(28, arrival(route).pack().count(LOGS), "worn " + worn + ": " + route);
        }
    }

    @Test
    void the_fare_is_never_banked_for_room_though_above_its_target() {
        int bar = ItemId.GOLD_BAR.id();
        ServerData env = RealDefs.serverData();
        assertTrue(
                env.item(bar).basePrice() > 100L * env.item(COINS).basePrice(), "the premise: the coins are cheaper");
        LoadoutSpec loadout = new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.LOGS, 28, 28, 28, false),
                        new LoadoutSpec.Item(ItemId.COINS, 0, 0, 1000, false),
                        new LoadoutSpec.Item(ItemId.GOLD_BAR, 0, 0, 1, false)),
                false,
                false);
        List<InventorySlot> slots = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            slots.add(new InventorySlot(slots.size(), LOGS, 1, false));
        }
        slots.add(new InventorySlot(slots.size(), COINS, 100, false));
        slots.add(new InventorySlot(slots.size(), bar, 1, false));
        Route route = planned(account(slots), loadout);
        Route.Hop buy = buy(route);
        assertNotNull(buy, route.toString());
        assertTrue(buy.pack().count(COINS) >= 6, "the fare kept: " + route);
        assertEquals(0, buy.pack().count(bar), "the bar banked: " + route);

        LoadoutSpec noBar = new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.LOGS, 29, 29, 29, false),
                        new LoadoutSpec.Item(ItemId.COINS, 0, 0, 1000, false)),
                false,
                false);
        assertInstanceOf(PlanResult.NoRoute.class, plan(account(29, 100, false), noBar));
    }

    private static List<InventorySlot> withAmulet(boolean worn) {
        List<InventorySlot> slots = new ArrayList<>();
        for (int i = 0; i < 28; i++) {
            slots.add(new InventorySlot(slots.size(), LOGS, 1, false));
        }
        slots.add(new InventorySlot(slots.size(), COINS, 100, false));
        slots.add(new InventorySlot(slots.size(), ItemId.AMULET_OF_ACCURACY.id(), 1, worn));
        return slots;
    }

    private static LoadoutSpec logsBand(int min, int target, int max) {
        return new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.LOGS, min, target, max, false),
                        new LoadoutSpec.Item(ItemId.COINS, 0, 100, 1000, false)),
                false,
                false);
    }

    private static AccountFacts account(int logs, int coins, boolean pass) {
        List<InventorySlot> slots = new ArrayList<>();
        for (int i = 0; i < logs; i++) {
            slots.add(new InventorySlot(slots.size(), LOGS, 1, false));
        }
        if (coins > 0) {
            slots.add(new InventorySlot(slots.size(), COINS, coins, false));
        }
        if (pass) {
            slots.add(new InventorySlot(slots.size(), PASS, 1, false));
        }
        return account(slots);
    }

    private static AccountFacts account(List<InventorySlot> slots) {
        ServerData env = RealDefs.serverData();
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 70, 70, 0);
        }
        MutableInventory inv = new MutableInventory();
        inv.setAll(slots);
        Bank bank = Bank.of(List.of(new BankSlot(0, ItemId.COINS, 5000)));
        return new AccountFacts.Snapshot(
                env, inv.snapshot(), bank, stats.snapshot(), new MutableQuests().snapshot(), NORTH);
    }

    private static LoadoutSpec logs(int n) {
        return new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.LOGS, n, n, n, false)), false, false);
    }

    private static LoadoutSpec logsAndCoins(int n) {
        return new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.LOGS, n, n, n, false),
                        new LoadoutSpec.Item(ItemId.COINS, 0, 100, 1000, false)),
                false,
                false);
    }

    private static RouteContext ctx(AccountFacts a) {
        return new RouteContext(a, NavPolicy.DEFAULT, RealDefs.serverData());
    }

    private static PlanResult plan(AccountFacts a, LoadoutSpec loadout) {
        return RealDefs.pathfinder().nav().plan(new PlanRequest(a, NORTH, DESERT, loadout, NavPolicy.DEFAULT));
    }

    private static Route planned(AccountFacts a, LoadoutSpec loadout) {
        return assertInstanceOf(PlanResult.Planned.class, plan(a, loadout), "no route")
                .route();
    }

    private static @Nullable Route.Hop buy(Route route) {
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.Hop h && h.transport().equals("SHANTAY_PASS_INTO_DESERT#buy")) {
                return h;
            }
        }
        return null;
    }

    private static Route.Arrive arrival(Route route) {
        return (Route.Arrive) route.steps().get(route.steps().size() - 1);
    }
}
