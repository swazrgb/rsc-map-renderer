package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.QuestId;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.BerthPolicy;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.planner.InputsProbeBot;
import org.junit.jupiter.api.Test;

class FoodLiftsHitsGatesTest {

    // Either side of the Legends' ruined wall, whose failed jump deals 6:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestWallObjects.java#L121
    private static final Point WEST = Point.of(455, 3729);

    private static final Point EAST = Point.of(457, 3727);

    private static final ServerData ENV = RealDefs.serverData();

    @Test
    void foodAboardCrossesOnTheBaseLevel() {
        assertFalse(wallPlans(6, 99, List.of()), "6 hits and nothing to eat: the wall can kill");
        assertTrue(wallPlans(6, 99, List.of(slot(ItemId.SHARK, false))), "a shark aboard eats back to 99");
        assertFalse(wallPlans(6, 99, List.of(slot(ItemId.SHARK, true))), "a noted shark is not a meal");
        assertFalse(wallPlans(6, 99, List.of(slot(ItemId.CHEESE, false))), "edible, but no seat eats cheese");
        assertFalse(wallPlans(6, 6, List.of(slot(ItemId.SHARK, false))), "no meal lifts past the base level");
    }

    @Test
    void aHurtSeatBanksForFoodAndComesHomeOverTheWalkway() {
        Map<Skill, Integer> skills = new LinkedHashMap<>();
        for (Skill s : Skill.values()) {
            skills.put(s, 99);
        }
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (QuestId q : QuestId.values()) {
            quests.put(q.id(), -1);
        }
        quests.put(QuestId.LEGENDS_QUEST.id(), 7);
        Map<Integer, Integer> bank = Map.of(
                ItemId.SHARK.id(), 470,
                ItemId.MACHETTE.id(), 1,
                ItemId.RUNE_AXE.id(), 1,
                ItemId.LOCKPICK.id(), 1,
                ItemId.RUNE_PICKAXE.id(), 1,
                ItemId.UNPOWERED_ORB.id(), 1,
                ItemId.WATER_RUNE.id(), 30,
                ItemId.COSMIC_RUNE.id(), 3);
        int amulet = ItemId.CHARGED_DRAGONSTONE_AMULET.id();
        InputsProbeBot holdings =
                new InputsProbeBot(ENV, skills, Map.of(amulet, 1), Set.of(amulet), bank, quests, CacheValues.EMPTY);
        // Where a fall off the rocky walkway lands; the walkway back deals up to 31.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestCaveAgility.java#L68
        AccountFacts hurt = new AccountFacts.Snapshot(
                ENV, holdings.inventory(), holdings.bank(), stats(19, 71), holdings.quests(), Point.of(421, 3699));
        LoadoutSpec sharks =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.SHARK, 2, 11, 11, false)), false, true);
        NavPolicy legends = NavPolicy.DEFAULT
                .withDanger(NavPolicy.DangerAvoidance.OFF)
                .withBerth(BerthPolicy.none())
                .withTeleportJewellery(true);

        PlanResult r = RealDefs.pathfinder()
                .nav()
                .plan(new PlanRequest(hurt, Point.of(421, 3699), Point.of(411, 3736), sharks, legends));

        Route route = assertInstanceOf(PlanResult.Planned.class, r).route();
        boolean banked = false;
        boolean walkway = false;
        for (Route.Step s : route.steps()) {
            banked |= s instanceof Route.BankStop;
            walkway |= banked && s instanceof Route.Hop h && h.transport().startsWith("LEGENDS_CAVE_ROCKY_WALKWAY");
        }
        assertTrue(banked, "the trip banks for its sharks");
        assertTrue(walkway, "and comes home over the walkway on them");
    }

    private static boolean wallPlans(int hits, int base, List<InventorySlot> pack) {
        InputsProbeBot quests =
                new InputsProbeBot(ENV, Map.of(), Map.of(), Set.of(), Map.of(), allDone(), CacheValues.EMPTY);
        AccountFacts bot = new AccountFacts.Snapshot(
                ENV, Inventory.of(pack), quests.bank(), stats(hits, base), quests.quests(), WEST);
        return RealDefs.pathfinder().nav().plan(new PlanRequest(bot, WEST, EAST, LoadoutSpec.any(), NavPolicy.DEFAULT))
                instanceof PlanResult.Planned;
    }

    private static Stats stats(int hits, int base) {
        MutableStats stats = new MutableStats();
        for (Skill s : Skill.values()) {
            stats.setSkill(s.ordinal(), s == Skill.HITS ? hits : 99, s == Skill.HITS ? base : 99, 0);
        }
        return stats.snapshot();
    }

    private static Map<Integer, Integer> allDone() {
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (QuestId q : QuestId.values()) {
            quests.put(q.id(), -1);
        }
        return quests;
    }

    private static InventorySlot slot(ItemId item, boolean noted) {
        return new InventorySlot(0, item.id(), 1, false, noted);
    }
}
