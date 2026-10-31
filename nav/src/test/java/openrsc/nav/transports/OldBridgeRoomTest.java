package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.RouteContext;
import openrsc.nav.planner.InputsProbeBot;
import org.junit.jupiter.api.Test;

/**
 * The old bridge makes its three lit arrows one at a time, and wants two free slots at its start. The
 * cloth does not stack; the wrap takes the cloth and an arrow before it gives the wrapped arrow, and
 * the fire takes that before it gives a lit one, which stack. So from the second arrow on, the lit
 * stack holds a slot beside the arrows' while the next cloth needs one.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L43">UndergroundPassMechanismMap1.onUseInv</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L61">UndergroundPassMechanismMap1.onUseLoc</a>
 */
class OldBridgeRoomTest {

    private static final ServerData ENV = RealDefs.serverData();

    private static final Point ARDOUGNE = Point.of(613, 593);

    private static final Point PAST_BRIDGE = Point.of(709, 3420);

    private static final Point DRAYNOR = Point.of(214, 632);

    private static final Point IBAN_TEMPLE = Point.of(786, 3469);

    private static final int ARROWS = ItemId.BRONZE_ARROWS.id();
    private static final int CLOTH = ItemId.DAMP_CLOTH.id();
    private static final int WRAPPED = ItemId.ARROW.id();
    private static final int LIT = ItemId.LIT_ARROW.id();
    private static final int LOGS = ItemId.LOGS.id();

    @Test
    void the_items_stack_as_the_derivation_reads_them() {
        assertTrue(ENV.isStackable(ARROWS));
        assertFalse(ENV.isStackable(CLOTH), "the cloth takes a slot of its own");
        assertFalse(ENV.isStackable(WRAPPED));
        assertTrue(ENV.isStackable(LIT), "the lit arrows gather in one slot");
    }

    @Test
    void two_free_slots_cross_without_a_stop() {
        Route route = planned(pack(25, Map.of(ARROWS, 3)), pinned(25));
        Route.Hop crossing = crossing(route);
        assertNotNull(crossing, "the crossing: " + route);
        assertNull(stopBefore(route, crossing), route.toString());
        assertEquals(2, crossing.pack().emptySlots(), route.toString());
        assertEquals(Map.of(ARROWS, 3L), crossing.fare(), route.toString());
        assertEquals(25, arrival(route).pack().count(LOGS), route.toString());
        for (int id : new int[] {ARROWS, CLOTH, WRAPPED, LIT}) {
            assertEquals(0, arrival(route).pack().count(id), ItemId.nameOf(id) + ": " + route);
        }
    }

    @Test
    void one_free_slot_is_one_short_of_the_peak() {
        assertInstanceOf(PlanResult.NoRoute.class, plan(pack(26, Map.of(ARROWS, 3)), pinned(26)));
        assertInstanceOf(PlanResult.NoRoute.class, plan(pack(27, Map.of(ARROWS, 3)), pinned(27)), "nor a full pack");
    }

    @Test
    void an_any_pack_banks_unnamed_slots_for_the_room_and_keeps_its_credentials() {
        for (int logs : new int[] {26, 27}) {
            Route route = planned(pack(logs, Map.of(ARROWS, 3)), LoadoutSpec.any());
            Route.Hop crossing = crossing(route);
            assertNotNull(crossing, "the crossing: " + route);
            assertNotNull(stopBefore(route, crossing), route.toString());
            assertEquals(2, crossing.pack().emptySlots(), route.toString());
            assertEquals(1, crossing.pack().count(ItemId.SHORTBOW.id()), "the bow fires the arrows: " + route);
            assertEquals(3, crossing.pack().count(ARROWS), logs + " logs: " + route);
        }
    }

    @Test
    void credentials_are_never_banked_for_room() {
        List<LoadoutSpec.Requirement> items = new ArrayList<>();
        items.add(new LoadoutSpec.Item(ItemId.SHORTBOW, 0, 0, 1, false));
        items.add(new LoadoutSpec.Item(ItemId.ROPE, 1, 1, 1, false));
        items.add(new LoadoutSpec.Item(ItemId.LOGS, 26, 26, 26, false));
        items.add(new LoadoutSpec.Item(ItemId.BRONZE_ARROWS, 0, 0, 50, false));
        PlanResult result = plan(pack(26, Map.of(ARROWS, 3)), new LoadoutSpec(items));
        assertInstanceOf(PlanResult.NoRoute.class, result, result::toString);

        items.set(1, new LoadoutSpec.Item(ItemId.ROPE, 0, 0, 1, false));
        Route route = planned(pack(26, Map.of(ARROWS, 3)), new LoadoutSpec(items));
        Route.BankStop stop = stopBefore(route, crossing(route));
        assertNotNull(stop, route.toString());
        assertEquals(0, stop.leaving().count(ItemId.ROPE.id()), route.toString());
        assertEquals(1, stop.leaving().count(ItemId.SHORTBOW.id()), route.toString());
    }

    @Test
    void stock_above_its_target_is_banked_for_room() {
        List<LoadoutSpec.Requirement> items = new ArrayList<>(pinned(27).requirements());
        items.set(2, new LoadoutSpec.Item(ItemId.LOGS, 25, 25, 27, false));
        Route route = planned(pack(27, Map.of(ARROWS, 3)), new LoadoutSpec(items));
        Route.Hop crossing = crossing(route);
        assertNotNull(crossing, "the crossing: " + route);
        Route.BankStop stop = stopBefore(route, crossing);
        assertNotNull(stop, route.toString());
        assertEquals(25, stop.leaving().count(LOGS), route.toString());
        assertEquals(2, crossing.pack().emptySlots(), route.toString());
        assertEquals(25, arrival(route).pack().count(LOGS), route.toString());
    }

    @Test
    void a_stop_that_draws_the_arrows_also_makes_the_chains_room() {
        Route route = planned(pack(28, Map.of(), Map.of(ARROWS, 10)), LoadoutSpec.any());
        Route.Hop crossing = crossing(route);
        assertNotNull(crossing, "the crossing: " + route);
        assertNotNull(stopBefore(route, crossing), route.toString());
        assertEquals(3, crossing.pack().count(ARROWS), "exactly the fare: " + route);
        assertEquals(
                2, crossing.pack().emptySlots(), "the cloth's slot and the lit stack's beside the arrows: " + route);
    }

    @Test
    void a_pack_filling_target_gives_way_to_the_chains_room() {
        AccountFacts bot = pack(0, Map.of(), Map.of(ItemId.SHARK.id(), 100, ARROWS, 10));
        for (boolean bankWhenFull : new boolean[] {false, true}) {
            Route route = planned(bot, sharks(1, 28, bankWhenFull));
            Route.Hop crossing = crossing(route);
            assertNotNull(crossing, "the crossing: " + route);
            Route.BankStop stop = stopBefore(route, crossing);
            assertNotNull(stop, route.toString());
            assertEquals(3, crossing.pack().count(ARROWS), route.toString());
            assertEquals(2, crossing.pack().emptySlots(), route.toString());
            assertEquals(
                    30 - 2 - 1 - 2,
                    stop.leaving().count(ItemId.SHARK.id()),
                    "bow, rope, arrows, room, bankWhenFull " + bankWhenFull + ": " + route);
        }
    }

    @Test
    void a_min_that_leaves_no_room_has_no_route() {
        AccountFacts bot = pack(0, Map.of(), Map.of(ItemId.SHARK.id(), 100, ARROWS, 10));
        assertInstanceOf(PlanResult.NoRoute.class, plan(bot, sharks(26, 28, false)));
        planned(bot, sharks(25, 28, false));
    }

    @Test
    void the_room_is_read_off_the_pack_the_stop_leaves_with_its_fares() {
        ServerData coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
        Map<Integer, Integer> carried = new LinkedHashMap<>();
        carried.put(ItemId.SHORTBOW.id(), 1);
        carried.put(ItemId.ROPE.id(), 1);
        carried.put(ItemId.COINS.id(), 300);
        carried.put(LOGS, 1);
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (QuestId q : QuestId.values()) {
            quests.put(q.id(), -1);
        }
        AccountFacts bot = new InputsProbeBot(
                coleslaw,
                Map.of(),
                carried,
                Set.of(),
                Map.of(ItemId.SHARK.id(), 100, ARROWS, 10, ItemId.COINS.id(), 100_000),
                quests,
                CacheValues.EMPTY);
        List<LoadoutSpec.Requirement> items =
                new ArrayList<>(sharks(1, 28, true).requirements());
        items.add(new LoadoutSpec.Item(ItemId.COINS, 0, 300, 25_000, false));
        PlanResult result = RealDefs.pathfinder(RealDefs.COLESLAW)
                .nav()
                .plan(new PlanRequest(
                        bot, DRAYNOR, IBAN_TEMPLE, new LoadoutSpec(items, true, false), NavPolicy.DEFAULT));
        Route route =
                assertInstanceOf(PlanResult.Planned.class, result, "no route").route();
        Route.Hop crossing = crossing(route);
        assertNotNull(crossing, "the crossing: " + route);
        assertEquals(2, crossing.pack().emptySlots(), route.toString());
        assertEquals(0, crossing.pack().count(LOGS), route.toString());
    }

    @Test
    void carried_arrows_still_get_the_chains_room_at_a_stop_the_food_forces() {
        AccountFacts bot = pack(0, Map.of(ARROWS, 10), Map.of(ItemId.SHARK.id(), 100));
        for (boolean namesArrows : new boolean[] {true, false}) {
            List<LoadoutSpec.Requirement> items =
                    new ArrayList<>(sharks(1, 28, false).requirements());
            if (namesArrows) {
                items.add(new LoadoutSpec.Item(ItemId.BRONZE_ARROWS, 0, 0, 50, false));
            }
            Route route = planned(bot, new LoadoutSpec(items));
            Route.Hop crossing = crossing(route);
            assertNotNull(crossing, "the crossing: " + route);
            Route.BankStop stop = stopBefore(route, crossing);
            assertNotNull(stop, route.toString());
            assertEquals(2, crossing.pack().emptySlots(), "names arrows " + namesArrows + ": " + route);
            assertEquals(
                    30 - 2 - 1 - 2,
                    stop.leaving().count(ItemId.SHARK.id()),
                    "names arrows " + namesArrows + ": " + route);
        }
    }

    @Test
    void the_room_shares_the_slots_a_lowered_min_reserves() {
        AccountFacts bot = pack(0, Map.of(), Map.of(ItemId.SHARK.id(), 100, ARROWS, 10));
        List<LoadoutSpec.Requirement> items =
                new ArrayList<>(sharks(25, 28, false).requirements());
        items.add(new LoadoutSpec.Item(ItemId.LAW_RUNE, 1, 1, 1, false));
        LoadoutSpec loadout = new LoadoutSpec(items);
        assertEquals(1, loadout.reachable(bot.inventory(), bot.bank(), ENV).reserve(), "the premise");
        Route route = planned(bot, loadout);
        Route.Hop crossing = crossing(route);
        assertNotNull(crossing, "the crossing: " + route);
        assertEquals(2, crossing.pack().emptySlots(), route.toString());
        assertEquals(25, stopBefore(route, crossing).leaving().count(ItemId.SHARK.id()), route.toString());
    }

    private static LoadoutSpec sharks(int min, int target, boolean bankWhenFull) {
        return new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.SHORTBOW, 1, 1, 1, false),
                        new LoadoutSpec.Item(ItemId.ROPE, 1, 1, 1, false),
                        new LoadoutSpec.Pool(List.of(ItemId.SHARK, ItemId.CAKE), min, target, target)),
                bankWhenFull,
                false);
    }

    @Test
    void a_stack_of_arrows_still_wants_two_slots() {
        Route route = planned(pack(25, Map.of(ARROWS, 50)), pinned(25));
        Route.Hop crossing = crossing(route);
        assertNotNull(crossing, "the crossing: " + route);
        assertNull(stopBefore(route, crossing), route.toString());
        assertEquals(2, crossing.pack().emptySlots(), route.toString());
        assertEquals(Map.of(ARROWS, 3L), crossing.fare(), route.toString());
        assertEquals(47, arrival(route).pack().count(ARROWS), route.toString());

        assertInstanceOf(PlanResult.NoRoute.class, plan(pack(26, Map.of(ARROWS, 50)), pinned(26)));
    }

    @Test
    void the_gate_opens_on_the_room_the_chain_wants_from_its_start() {
        UndergroundPassOldBridgeTransport bridge = theCrossing();
        for (int free = 0; free <= 3; free++) {
            AccountFacts bot = pack(30 - 3 - free, Map.of(ARROWS, 3));
            assertEquals(free, bot.inventory().emptySlots(), "the premise");
            int wanted = UndergroundPassOldBridgeTransport.roomWanted(bot.inventory());
            assertEquals(UndergroundPassOldBridgeTransport.PEAK_ROOM, wanted);
            assertEquals(
                    free >= wanted,
                    bridge.prereq().test(new RouteContext(bot, NavPolicy.DEFAULT, ENV)),
                    free + " free");
        }
    }

    @Test
    void roomWanted_is_the_rest_of_the_chains_peak() {
        int checked = 0;
        for (int arrowStack : new int[] {3, 50}) {
            for (int lit = 0; lit < UndergroundPassOldBridgeTransport.LIT_ARROWS; lit++) {
                for (int link = 0; link < 3; link++) {
                    int wrapped = link == 2 ? 1 : 0;
                    int arrows = arrowStack - lit - wrapped;
                    Map<Integer, Integer> extra = new LinkedHashMap<>();
                    if (arrows > 0) {
                        extra.put(ARROWS, arrows);
                    }
                    if (lit > 0) {
                        extra.put(LIT, lit);
                    }
                    if (link == 1) {
                        extra.put(CLOTH, 1);
                    }
                    if (wrapped > 0) {
                        extra.put(WRAPPED, 1);
                    }
                    int least = leastRoom(lit, link == 1, wrapped == 1, arrows);
                    assertEquals(
                            least,
                            UndergroundPassOldBridgeTransport.roomWanted(
                                    pack(0, extra).inventory()),
                            "lit " + lit + ", link " + link + ", arrows " + arrows);
                    checked++;
                }
            }
        }
        assertEquals(18, checked);
        assertEquals(
                0,
                UndergroundPassOldBridgeTransport.roomWanted(
                        pack(0, Map.of(LIT, 3)).inventory()),
                "done");
    }

    private static int leastRoom(int lit, boolean cloth, boolean wrapped, int arrows) {
        for (int free = 0; ; free++) {
            if (completes(free, lit, cloth, wrapped, arrows)) {
                return free;
            }
        }
    }

    // One link at a time, furthest first: the fire turns the wrapped arrow lit, freeing its slot once
    // a lit stack exists; the wrap turns cloth and arrow into a wrapped arrow, freeing the arrows' slot
    // with the last of them; a new cloth needs a free slot.
    private static boolean completes(int free, int lit, boolean cloth, boolean wrapped, int arrows) {
        while (lit < UndergroundPassOldBridgeTransport.LIT_ARROWS) {
            if (wrapped) {
                wrapped = false;
                free += lit > 0 ? 1 : 0;
                lit++;
            } else if (cloth) {
                cloth = false;
                arrows--;
                free += arrows == 0 ? 1 : 0;
                wrapped = true;
            } else if (free < 1) {
                return false;
            } else {
                free--;
                cloth = true;
            }
        }
        return true;
    }

    @Test
    void the_crossing_spends_its_three_arrows() {
        UndergroundPassOldBridgeTransport bridge = theCrossing();
        assertEquals(
                List.of(new Transport.Consumed(ItemId.BRONZE_ARROWS, 3)),
                bridge.consumes(pack(20, Map.of(ARROWS, 50))));
        assertEquals(
                List.of(new Transport.Consumed(ItemId.BRONZE_ARROWS, 3)),
                bridge.consumes(pack(20, Map.of(), Map.of(ARROWS, 50))),
                "banked: the arrows a stop draws");
        assertEquals(
                List.of(new Transport.Consumed(ItemId.IRON_ARROWS, 3)),
                bridge.consumes(pack(20, Map.of(ItemId.IRON_ARROWS.id(), 5), Map.of(ARROWS, 50))),
                "carried first");
    }

    private static UndergroundPassOldBridgeTransport theCrossing() {
        for (Transport t : UndergroundPassOldBridgeTransport.forWorld(ENV, "OLD_BRIDGE")) {
            if (t instanceof UndergroundPassOldBridgeTransport bridge) {
                return bridge;
            }
        }
        throw new AssertionError("the premise: the world places the old bridge");
    }

    private static AccountFacts pack(int logs, Map<Integer, Integer> extra) {
        return pack(logs, extra, Map.of());
    }

    private static AccountFacts pack(int logs, Map<Integer, Integer> extra, Map<Integer, Integer> bank) {
        Map<Integer, Integer> items = new LinkedHashMap<>();
        items.put(ItemId.SHORTBOW.id(), 1);
        items.put(ItemId.ROPE.id(), 1);
        if (logs > 0) {
            items.put(LOGS, logs);
        }
        items.putAll(extra);
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (QuestId q : QuestId.values()) {
            quests.put(q.id(), 0);
        }
        quests.put(QuestId.BIOHAZARD.id(), -1);
        quests.put(QuestId.UNDERGROUND_PASS.id(), 2); // the stage the crossing asks for
        InputsProbeBot holdings = new InputsProbeBot(ENV, Map.of(), items, Set.of(), bank, quests, CacheValues.EMPTY);
        return new AccountFacts.Snapshot(
                ENV, holdings.inventory(), holdings.bank(), holdings.stats(), holdings.quests(), ARDOUGNE);
    }

    private static LoadoutSpec pinned(int logs) {
        List<LoadoutSpec.Requirement> items = new ArrayList<>();
        items.add(new LoadoutSpec.Item(ItemId.SHORTBOW, 1, 1, 1, false));
        items.add(new LoadoutSpec.Item(ItemId.ROPE, 1, 1, 1, false));
        items.add(new LoadoutSpec.Item(ItemId.LOGS, logs, logs, logs, false));
        items.add(new LoadoutSpec.Item(ItemId.BRONZE_ARROWS, 0, 0, 50, false));
        return new LoadoutSpec(items);
    }

    private static PlanResult plan(AccountFacts bot, LoadoutSpec loadout) {
        return RealDefs.pathfinder()
                .nav()
                .plan(new PlanRequest(bot, bot.pos(), PAST_BRIDGE, loadout, NavPolicy.DEFAULT));
    }

    private static Route planned(AccountFacts bot, LoadoutSpec loadout) {
        return assertInstanceOf(PlanResult.Planned.class, plan(bot, loadout), "no route")
                .route();
    }

    private static Route.@Nullable Hop crossing(Route route) {
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.Hop h && h.transport().startsWith("UNDERGROUND_PASS_OLD_BRIDGE")) {
                return h;
            }
        }
        return null;
    }

    private static Route.@Nullable BankStop stopBefore(Route route, Route.Hop hop) {
        Route.BankStop last = null;
        for (Route.Step s : route.steps()) {
            if (s == hop) {
                return last;
            }
            if (s instanceof Route.BankStop b) {
                last = b;
            }
        }
        return null;
    }

    private static Route.Arrive arrival(Route route) {
        return (Route.Arrive) route.steps().get(route.steps().size() - 1);
    }
}
