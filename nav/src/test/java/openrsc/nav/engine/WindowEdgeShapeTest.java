package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

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
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WindowEdgeShapeTest {

    @Test
    @DisplayName("one tile either side of the window's edge plans the same shape")
    void sameShapeAcrossTheEdge() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        MutableStats stats = new MutableStats();
        Map<String, Integer> lv = Map.of("HITS", 10, "FISHING", 14, "MAGIC", 19, "COOKING", 7, "CRAFTING", 9);
        for (Skill sk : Skill.values()) {
            int l = lv.getOrDefault(sk.name(), 1);
            stats.setSkill(sk.ordinal(), l, l, 0);
        }
        MutableInventory pack = new MutableInventory();
        int[][] inv = {
            {1263, 1, 0},
            {33, 15, 0},
            {31, 5, 0},
            {42, 5, 0},
            {10, 300, 0},
            {766, 1, 1},
            {211, 1, 0},
            {237, 1, 0},
            {21, 1, 0},
            {21, 1, 0},
            {767, 1, 0},
            {770, 1, 0}
        };
        for (int i = 0; i < inv.length; i++) {
            pack.setSlot(i, new InventorySlot(i, inv[i][0], inv[i][1], inv[i][2] == 1));
        }
        MutableBank bank = new MutableBank();
        bank.setSlot(0, new BankSlot(0, ItemId.COINS.id(), 42663));
        bank.setSlot(1, new BankSlot(1, ItemId.CHOCOLATE_DUST.id(), 1));
        MutableQuests quests = new MutableQuests();
        quests.setStage(34, 2);
        LoadoutSpec kit = new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.SLEEPING_BAG, 1, 1, 1, false),
                        new LoadoutSpec.Item(ItemId.COINS, 300, 300, 300, false),
                        new LoadoutSpec.Item(ItemId.GASMASK, 1, 1, 1, true),
                        new LoadoutSpec.Item(ItemId.SPADE, 1, 1, 1, false),
                        new LoadoutSpec.Item(ItemId.ROPE, 1, 1, 1, false),
                        new LoadoutSpec.Item(ItemId.BUCKET, 4, 4, 5, false),
                        new LoadoutSpec.Item(ItemId.PICTURE, 1, 1, 1, false),
                        new LoadoutSpec.Item(ItemId.CHOCOLATE_DUST, 0, 1, 1, false),
                        new LoadoutSpec.Item(ItemId.CHOCOLATY_MILK, 1, 1, 1, false),
                        new LoadoutSpec.Item(ItemId.AIR_RUNE, 3, 15, 15, false),
                        new LoadoutSpec.Item(ItemId.FIRE_RUNE, 1, 5, 5, false),
                        new LoadoutSpec.Item(ItemId.LAW_RUNE, 1, 5, 5, false)),
                false,
                false);
        NavPolicy danger = new NavPolicy(NavPolicy.DangerAvoidance.ON, false, false, Map.of(), false);
        Point goal = Point.of(362, 602);
        // 48 tiles off, the window's edge: the local field prices the goal. One tile further, only the
        // hierarchy does.
        Point inside = Point.of(314, 601);
        Point outside = Point.of(313, 600);
        for (Point from : List.of(inside, outside)) {
            AccountFacts facts = new AccountFacts.Snapshot(
                    env, pack.snapshot(), bank.snapshot(), stats.snapshot(), quests.snapshot(), from);
            PlanResult r = pf.nav().plan(new PlanRequest(facts, from, goal, kit, danger));
            Route route = assertInstanceOf(PlanResult.Planned.class, r, "plans from " + from)
                    .route();
            assertFalse(
                    route.steps().stream().anyMatch(Route.BankStop.class::isInstance),
                    "no stop for a target-only band from " + from + ": " + route.steps());
            assertFalse(
                    route.steps().stream().anyMatch(Route.Hop.class::isInstance),
                    "a plain walk from " + from + ": " + route.steps());
            int est = pf.nav().estimate(facts, from, goal, danger).deci();
            assertEquals(route.totalCostDeci(), est, "plan and estimate agree from " + from);
        }
    }
}
