package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class OldBridgeFareTopUpTest {

    private static final ServerData ENV = RealDefs.serverData();

    private static final Point ARDOUGNE = Point.of(613, 593);

    private static final Point PAST_BRIDGE = Point.of(709, 3420);

    private static final int ARROWS = ItemId.BRONZE_ARROWS.id();
    private static final int SHARK = ItemId.SHARK.id();

    /** Names neither the arrows nor the bucket, and wants more sharks than are carried. */
    private static final LoadoutSpec KIT = new LoadoutSpec(
            List.of(
                    new LoadoutSpec.Item(ItemId.SHORTBOW, 1, 1, 1, false),
                    new LoadoutSpec.Item(ItemId.ROPE, 1, 1, 1, false),
                    new LoadoutSpec.Item(ItemId.SHARK, 1, 24, 24, false)),
            false,
            false);

    @Test
    void aStopThatTopsUpTheLoadoutKeepsTheCarriedFare() {
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (QuestId q : QuestId.values()) {
            quests.put(q.id(), 0);
        }
        quests.put(QuestId.BIOHAZARD.id(), -1);
        quests.put(QuestId.UNDERGROUND_PASS.id(), 2);
        Map<Integer, Integer> pack =
                Map.of(ItemId.SHORTBOW.id(), 1, ItemId.ROPE.id(), 1, ARROWS, 3, SHARK, 5, ItemId.BUCKET.id(), 1);
        // Fare items of other transports: owned, never needed here.
        Map<Integer, Integer> bank =
                Map.of(SHARK, 30, ItemId.LAW_RUNE.id(), 10, ItemId.FIRE_RUNE.id(), 10, ItemId.AIR_RUNE.id(), 10);
        AccountFacts bot = new InputsProbeBot(ENV, Map.of(), pack, Set.of(), bank, quests, CacheValues.EMPTY);
        PlanResult r =
                RealDefs.pathfinder().nav().plan(new PlanRequest(bot, ARDOUGNE, PAST_BRIDGE, KIT, NavPolicy.DEFAULT));
        Route route = assertInstanceOf(PlanResult.Planned.class, r).route();
        Route.BankStop stop = null;
        Route.Hop crossing = null;
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.BankStop b && crossing == null) {
                stop = b;
            } else if (s instanceof Route.Hop h && h.transport().startsWith("UNDERGROUND_PASS_OLD_BRIDGE")) {
                crossing = h;
            }
        }
        assertTrue(stop != null && crossing != null, route.steps().toString());
        assertEquals(24, stop.leaving().count(SHARK), "the stop tops the sharks to target: " + stop);
        assertEquals(3, stop.leaving().count(ARROWS), "and keeps exactly the fare: " + stop);
        assertEquals(0, stop.leaving().count(ItemId.BUCKET.id()), "and banks what the loadout does not name");
        assertEquals(Map.of(ARROWS, 3L), crossing.fare());
    }
}
