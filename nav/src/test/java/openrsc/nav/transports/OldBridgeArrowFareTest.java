package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import openrsc.nav.planner.InputsProbeBot;
import org.junit.jupiter.api.Test;

class OldBridgeArrowFareTest {

    private static final ServerData ENV = RealDefs.serverData();

    private static final Point ARDOUGNE = Point.of(613, 593);

    private static final Point PAST_BRIDGE = Point.of(709, 3420);

    private static final LoadoutSpec KIT = new LoadoutSpec(List.of(
            new LoadoutSpec.Item(ItemId.SHORTBOW, 1, 1, 1, false), new LoadoutSpec.Item(ItemId.ROPE, 1, 1, 1, false)));

    @Test
    void exactlyThreeBankedArrowsAreDrawnAndNoneOfTheChainArrives() {
        Route route = assertInstanceOf(PlanResult.Planned.class, plan(Map.of(ItemId.BRONZE_ARROWS.id(), 10)))
                .route();
        Route.BankStop stop = null;
        Route.Hop crossing = null;
        Route.Arrive arrive = null;
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.BankStop b && crossing == null) {
                stop = b;
            } else if (s instanceof Route.Hop h && h.transport().startsWith("UNDERGROUND_PASS_OLD_BRIDGE")) {
                crossing = h;
            } else if (s instanceof Route.Arrive a) {
                arrive = a;
            }
        }
        assertTrue(stop != null, "a bank stop before the bridge: " + route.steps());
        assertEquals(
                UndergroundPassOldBridgeTransport.LIT_ARROWS,
                stop.leaving().count(ItemId.BRONZE_ARROWS.id()),
                "it draws exactly three of the ten: " + stop);
        assertTrue(crossing != null, "the crossing: " + route.steps());
        assertEquals(Point.of(702, 3421), crossing.entry(), "from Koftik's floor, where the cloth and fire are");
        assertEquals(Map.of(ItemId.BRONZE_ARROWS.id(), 3L), crossing.fare(), "three arrows are the fare: " + crossing);
        assertTrue(arrive != null, "it arrives: " + route.steps());
        for (int id :
                new int[] {ItemId.BRONZE_ARROWS.id(), ItemId.LIT_ARROW.id(), ItemId.ARROW.id(), ItemId.DAMP_CLOTH.id()
                }) {
            assertEquals(0, arrive.pack().count(id), "with none of the chain: " + ItemId.nameOf(id));
        }
    }

    @Test
    void twoArrowsAreNoFare() {
        assertFalse(
                plan(Map.of(ItemId.BRONZE_ARROWS.id(), 2)) instanceof PlanResult.Planned,
                "two banked: the crossing makes three, so nothing crosses");
    }

    @Test
    void noArrowsAnywhereIsAnHonestNoRoute() {
        assertFalse(plan(Map.of()) instanceof PlanResult.Planned, "no arrow carried or banked: nothing crosses");
    }

    private static PlanResult plan(Map<Integer, Integer> bank) {
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (QuestId q : QuestId.values()) {
            quests.put(q.id(), 0);
        }
        quests.put(QuestId.BIOHAZARD.id(), -1);
        quests.put(QuestId.UNDERGROUND_PASS.id(), 2); // the stage the crossing asks for
        Map<Integer, Integer> pack = Map.of(ItemId.SHORTBOW.id(), 1, ItemId.ROPE.id(), 1);
        InputsProbeBot holdings = new InputsProbeBot(ENV, Map.of(), pack, Set.of(), bank, quests, CacheValues.EMPTY);
        AccountFacts bot = new AccountFacts.Snapshot(
                ENV, holdings.inventory(), holdings.bank(), holdings.stats(), holdings.quests(), ARDOUGNE);
        return RealDefs.pathfinder().nav().plan(new PlanRequest(bot, ARDOUGNE, PAST_BRIDGE, KIT, NavPolicy.DEFAULT));
    }
}
