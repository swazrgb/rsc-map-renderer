package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.transports.NightshadePickupTransport;
import openrsc.nav.transports.SpecialCrossings;
import org.junit.jupiter.api.Test;

/**
 * A walk into the Ogre Enclave takes its nightshade off a Skavid cave's spawn on the way, when none
 * is owned.
 */
class NightshadePickupRouteTest {

    private static final Point BY_THE_GUARD = Point.of(661, 789);
    private static final Rect ENCLAVE = Rect.of(645, 3615, 645, 3615);
    private static final String GUARD = "WATCHTOWER_ENCLAVE_NIGHTSHADE_AFTER_QUEST";
    private static final String NORTH = SpecialCrossings.NIGHTSHADE_PICKUP_NORTH_CAVE.key();
    private static final String SOUTH = SpecialCrossings.NIGHTSHADE_PICKUP_SOUTH_CAVE.key();

    private static final LoadoutSpec CAMP = new LoadoutSpec(
            List.of(
                    new LoadoutSpec.Item(ItemId.SKAVID_MAP, 0, 0, 1, false),
                    new LoadoutSpec.Item(ItemId.LIT_BLACK_CANDLE, 0, 0, 1, false),
                    new LoadoutSpec.Item(ItemId.SHARK, 0, 10, 20, false)),
            false,
            false);

    @Test
    void with_no_nightshade_owned_a_stop_draws_the_cave_kit_and_the_spawn_is_the_way_in() {
        Route route = planned(account(BY_THE_GUARD, List.of(), caveKit()), BY_THE_GUARD, NavPolicy.DEFAULT);
        List<String> hops = hops(route);
        assertTrue(hops.contains(NORTH) || hops.contains(SOUTH), route.toString());
        assertTrue(hops.stream().noneMatch(GUARD::equals), route.toString());
        Route.BankStop stop = route.steps().stream()
                .filter(Route.BankStop.class::isInstance)
                .map(Route.BankStop.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("a stop draws the map and the light: " + route));
        assertEquals(1, stop.leaving().count(ItemId.SKAVID_MAP.id()), route.toString());
        assertEquals(1, stop.leaving().count(ItemId.LIT_BLACK_CANDLE.id()), route.toString());
    }

    @Test
    void a_banked_nightshade_is_drawn_for_the_guard_instead() {
        List<BankSlot> bank = new ArrayList<>(caveKit());
        bank.add(new BankSlot(bank.size(), ItemId.NIGHTSHADE, 3));
        Route route = planned(account(BY_THE_GUARD, List.of(), bank), BY_THE_GUARD, NavPolicy.DEFAULT);
        assertTrue(hops(route).contains(GUARD), route.toString());
        assertTrue(hops(route).stream().noneMatch(h -> h.equals(NORTH) || h.equals(SOUTH)), route.toString());
    }

    @Test
    void each_spawn_is_priced_as_its_wait_and_the_way_on_to_the_guard() {
        for (NightshadePickupTransport t : pickups()) {
            Route onward = planned(
                    account(t.spawn(), List.of(new InventorySlot(0, ItemId.NIGHTSHADE.id(), 1, false)), List.of()),
                    t.spawn(),
                    NavPolicy.DEFAULT);
            assertTrue(hops(onward).contains(GUARD), onward.toString());
            int wait = NightshadePickupTransport.RESPAWN_COST / 2;
            assertTrue(
                    Math.abs(t.cost() - wait - onward.totalCostDeci()) <= 50,
                    t.name() + " costs " + t.cost() + ", its wait " + wait + " and the way on "
                            + onward.totalCostDeci());
        }
    }

    @Test
    void a_queue_at_the_nearer_spawn_sends_the_route_to_the_other() {
        AccountFacts bot = account(BY_THE_GUARD, List.of(), caveKit());
        List<String> alone = hops(planned(bot, BY_THE_GUARD, NavPolicy.DEFAULT));
        String nearer = alone.contains(NORTH) ? NORTH : SOUTH;
        String other = nearer.equals(NORTH) ? SOUTH : NORTH;
        int base = pickups().stream()
                .filter(t -> t.name().equals(nearer))
                .findFirst()
                .orElseThrow()
                .cost();
        NavPolicy queued = NavPolicy.DEFAULT.cost(
                new openrsc.nav.TransportRef.Arm(nearer), base + 3 * NightshadePickupTransport.RESPAWN_COST);
        assertTrue(hops(planned(bot, BY_THE_GUARD, queued)).contains(other));
    }

    @Test
    void a_world_whose_guard_is_occupied_after_the_quest_has_no_pickup() {
        assertTrue(RealDefs.pathfinder().transports().stream().noneMatch(NightshadePickupTransport.class::isInstance));
    }

    private static List<NightshadePickupTransport> pickups() {
        return RealDefs.pathfinder(RealDefs.COLESLAW).transports().stream()
                .filter(NightshadePickupTransport.class::isInstance)
                .map(NightshadePickupTransport.class::cast)
                .toList();
    }

    private static List<BankSlot> caveKit() {
        return List.of(new BankSlot(0, ItemId.SKAVID_MAP, 1), new BankSlot(1, ItemId.LIT_BLACK_CANDLE, 1));
    }

    private static List<String> hops(Route route) {
        return route.steps().stream()
                .filter(Route.Hop.class::isInstance)
                .map(s -> ((Route.Hop) s).transport())
                .toList();
    }

    private static AccountFacts account(Point at, List<InventorySlot> slots, List<BankSlot> banked) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 70, 70, 0);
        }
        MutableInventory inv = new MutableInventory();
        inv.setAll(slots);
        MutableQuests quests = new MutableQuests();
        quests.setStage(QuestId.WATCHTOWER.id(), -1);
        return new AccountFacts.Snapshot(
                RealDefs.serverData(RealDefs.COLESLAW),
                inv.snapshot(),
                Bank.of(banked),
                stats.snapshot(),
                quests.snapshot(),
                at);
    }

    private static Route planned(AccountFacts a, Point from, NavPolicy policy) {
        PlanResult r =
                RealDefs.pathfinder(RealDefs.COLESLAW).nav().plan(new PlanRequest(a, from, ENCLAVE, CAMP, policy));
        return assertInstanceOf(PlanResult.Planned.class, r, "no route: " + r).route();
    }
}
