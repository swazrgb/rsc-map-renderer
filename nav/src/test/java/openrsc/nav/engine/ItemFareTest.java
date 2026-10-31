package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
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
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ItemFareTest {

    private static final Point STORE = Point.of(166, 3523);
    private static final Point JAKUT = Point.of(113, 3546);
    private static final Point AL_KHARID_BANK = Point.of(90, 694);

    private static final Point PORT_SARIM = Point.of(268, 650);
    private static final Point KARAMJA = Point.of(330, 713);

    private static final LoadoutSpec STAFF =
            new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.DRAMEN_STAFF, 1, 1, 1, false)), false, false);

    private static AccountFacts account(Point at, Map<ItemId, Integer> carried, Map<ItemId, Integer> banked) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 70, 70, 0);
        }
        MutableQuests quests = new MutableQuests();
        for (QuestId q : QuestId.values()) {
            quests.setStage(q.id(), -1);
        }
        List<InventorySlot> slots = new ArrayList<>();
        carried.forEach((item, n) -> {
            if (RealDefs.serverData().isStackable(item.id())) {
                slots.add(new InventorySlot(slots.size(), item.id(), n, false));
            } else {
                for (int i = 0; i < n; i++) {
                    slots.add(new InventorySlot(slots.size(), item.id(), 1, false));
                }
            }
        });
        MutableInventory inv = new MutableInventory();
        inv.setAll(slots);
        List<BankSlot> bank = new ArrayList<>();
        banked.forEach((item, n) -> {
            if (n > 0) {
                bank.add(new BankSlot(bank.size(), item, n));
            }
        });
        return new AccountFacts.Snapshot(
                RealDefs.serverData(), inv.snapshot(), Bank.of(bank), stats.snapshot(), quests.snapshot(), at);
    }

    private static AccountFacts withDiamonds(Point at, int carried, int banked, boolean staff) {
        Map<ItemId, Integer> pack = new java.util.LinkedHashMap<>();
        if (staff) {
            pack.put(ItemId.DRAMEN_STAFF, 1);
        }
        pack.put(ItemId.DIAMOND, carried);
        return account(at, pack, Map.of(ItemId.DIAMOND, banked));
    }

    private static PlanResult plan(AccountFacts a, Point to, LoadoutSpec loadout) {
        Pathfinder pf = RealDefs.pathfinder();
        return pf.nav()
                .plan(new PlanRequest(a, a.pos(), Rect.of(to.x(), to.y(), to.x(), to.y()), loadout, NavPolicy.DEFAULT));
    }

    private static Route planned(PlanResult r) {
        return assertInstanceOf(PlanResult.Planned.class, r, String.valueOf(r)).route();
    }

    private static Inventory arrival(Route route) {
        return ((Route.Arrive) route.steps().get(route.steps().size() - 1)).pack();
    }

    private static long withdrawn(Route route, ItemId item) {
        long n = 0;
        for (Route.Step s : route.steps()) {
            if (s instanceof Route.BankStop b) {
                n += Math.max(0, b.leaving().count(item.id()) - b.pack().count(item.id()));
            }
        }
        return n;
    }

    private static void assertWithdrawnThenPaid(Route route, ItemId item, long fare) {
        int stop = -1;
        int paid = -1;
        for (int i = 0; i < route.steps().size(); i++) {
            Route.Step s = route.steps().get(i);
            if (stop < 0
                    && s instanceof Route.BankStop b
                    && b.leaving().count(item.id()) > b.pack().count(item.id())) {
                stop = i;
            }
            if (paid < 0 && s instanceof Route.Hop h && h.fare().getOrDefault(item.id(), 0L) > 0) {
                paid = i;
                assertTrue(h.pack().count(item.id()) >= h.fare().get(item.id()), "the hop's own pack pays: " + route);
            }
        }
        assertTrue(stop >= 0 && paid > stop, "a stop withdraws the fare, then a hop pays it: " + route);
        assertEquals(fare, withdrawn(route, item), "the stop draws the fare, no more: " + route);
        assertEquals(fare, route.debits().get(item.id()), route.toString());
    }

    @Test
    @DisplayName("a loadout naming no diamond plans the market door off one banked: withdraw 1, cross, arrive with 0")
    void anEmptyLoadoutWithdrawsTheDiamondAndArrivesWithNone() {
        Route route = planned(plan(withDiamonds(STORE, 0, 1, false), JAKUT, LoadoutSpec.empty()));

        assertWithdrawnThenPaid(route, ItemId.DIAMOND, 1);
        assertEquals(0, arrival(route).count(ItemId.DIAMOND.id()), "the fare is spent, not carried: " + route);
    }

    @Test
    @DisplayName("a coin fare goes through the same code: an empty loadout withdraws the ship's 30 and arrives with 0")
    void aCoinFareIsTheSameShape() {
        AccountFacts a = account(PORT_SARIM, Map.of(), Map.of(ItemId.COINS, 30));
        Route route = planned(plan(a, KARAMJA, LoadoutSpec.empty()));

        assertWithdrawnThenPaid(route, ItemId.COINS, 30);
        assertEquals(0, arrival(route).count(ItemId.COINS.id()), route.toString());
    }

    @Test
    @DisplayName("no diamond carried or banked is an honest no route into the market")
    void noFareAnywhereIsNoRoute() {
        assertInstanceOf(PlanResult.NoRoute.class, plan(withDiamonds(STORE, 0, 0, false), JAKUT, LoadoutSpec.empty()));
        assertInstanceOf(PlanResult.NoRoute.class, plan(withDiamonds(STORE, 0, 0, true), JAKUT, STAFF));
    }

    @Test
    @DisplayName("two entries need two banked diamonds: one plans the first leg only, two plan the round trip")
    void theRoundTripNeedsASecondDiamond() {
        for (int banked : new int[] {1, 2}) {
            Route in = planned(plan(withDiamonds(STORE, 0, banked, true), JAKUT, STAFF));
            assertWithdrawnThenPaid(in, ItemId.DIAMOND, 1);
            assertEquals(0, arrival(in).count(ItemId.DIAMOND.id()));

            Route out = planned(plan(withDiamonds(JAKUT, 0, banked - 1, true), AL_KHARID_BANK, STAFF));
            assertTrue(out.debits().isEmpty(), "the ladder out costs nothing: " + out);
            assertEquals(0, withdrawn(out, ItemId.DIAMOND), out.toString());

            PlanResult again = plan(withDiamonds(AL_KHARID_BANK, 0, banked - 1, true), JAKUT, STAFF);
            if (banked == 1) {
                assertInstanceOf(PlanResult.NoRoute.class, again, "the only diamond was spent going in");
            } else {
                Route second = planned(again);
                assertWithdrawnThenPaid(second, ItemId.DIAMOND, 1);
                assertEquals(0, arrival(second).count(ItemId.DIAMOND.id()));
            }
        }
    }
}
