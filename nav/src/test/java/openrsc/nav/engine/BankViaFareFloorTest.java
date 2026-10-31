package openrsc.nav.engine;

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
import openrsc.gamedata.ServerData;
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

class BankViaFareFloorTest {

    private static final Point PORT_SARIM = Point.of(268, 650);
    private static final Rect KARAMJA = Rect.of(330, 713, 330, 713);

    private static final Point KARAMJA_DOCK = Point.of(325, 713);
    private static final Rect PORT_SARIM_DOCK = Rect.of(268, 648, 268, 648);

    private static AccountFacts account(int carried, int banked) {
        return account(PORT_SARIM, carried, banked);
    }

    private static AccountFacts account(Point at, int carried, int banked) {
        ServerData env = RealDefs.serverData();
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 70, 70, 0);
        }
        MutableQuests quests = new MutableQuests();
        for (QuestId q : QuestId.values()) {
            quests.setStage(q.id(), -1);
        }
        MutableInventory inv = new MutableInventory();
        inv.setAll(List.of(new InventorySlot(0, ItemId.COINS.id(), carried, false)));
        List<BankSlot> bank = new ArrayList<>();
        if (banked > 0) {
            bank.add(new BankSlot(0, ItemId.COINS, banked));
        }
        return new AccountFacts.Snapshot(env, inv.snapshot(), Bank.of(bank), stats.snapshot(), quests.snapshot(), at);
    }

    @Test
    @DisplayName("a route never arrives below a floor its fares spent into, whatever shape it takes")
    void everyShapeArrivesWithinTheFloor() {
        Pathfinder pf = RealDefs.pathfinder();
        LoadoutSpec coins =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.COINS, 100, 100, 1000, false)), false, false);
        for (int carried : new int[] {100, 110, 129, 130, 200}) {
            for (int banked : new int[] {0, 5000}) {
                AccountFacts a = account(carried, banked);
                PlanResult r = pf.nav().plan(new PlanRequest(a, PORT_SARIM, KARAMJA, coins, NavPolicy.DEFAULT));
                if (r instanceof PlanResult.Planned(Route route)) {
                    Route.Step last = route.steps().get(route.steps().size() - 1);
                    long arrived = ((Route.Arrive) last).pack().count(ItemId.COINS.id());
                    assertTrue(
                            arrived >= 100 && arrived <= 1000,
                            carried + " carried, " + banked + " banked: arrives with " + arrived + " — " + route);
                } else {
                    assertTrue(banked == 0 && carried < 130, carried + " carried, " + banked + " banked: " + r);
                }
            }
        }
    }

    @Test
    @DisplayName("a fare paid before the stop never leaves the stop's targets short")
    void aFareBeforeTheStopCountsAgainstItsTargets() {
        Pathfinder pf = RealDefs.pathfinder();
        LoadoutSpec coins =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.COINS, 360, 360, 360, false)), false, false);
        for (int carried : new int[] {361, 389, 390, 400}) {
            AccountFacts a = account(KARAMJA_DOCK, carried, 0);
            PlanResult r = pf.nav().plan(new PlanRequest(a, KARAMJA_DOCK, PORT_SARIM_DOCK, coins, NavPolicy.DEFAULT));
            if (r instanceof PlanResult.Planned(Route route)) {
                Route.Step last = route.steps().get(route.steps().size() - 1);
                long arrived = ((Route.Arrive) last).pack().count(ItemId.COINS.id());
                assertTrue(arrived == 360, carried + " carried: arrives with " + arrived + " — " + route);
            } else {
                assertTrue(carried < 390, carried + " carried: " + r);
            }
        }
    }

    @Test
    @DisplayName("a stop never plans a withdrawal the bank cannot cover once the fares before it are paid")
    void aFareBeforeAWithdrawStopIsDrawnFromWhatTheBankHolds() {
        Pathfinder pf = RealDefs.pathfinder();
        LoadoutSpec coins =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.COINS, 100, 100, 1000, false)), false, false);
        for (int[] split : new int[][] {{50, 50}, {50, 79}, {50, 80}, {50, 500}}) {
            AccountFacts a = account(KARAMJA_DOCK, split[0], split[1]);
            PlanResult r = pf.nav().plan(new PlanRequest(a, KARAMJA_DOCK, PORT_SARIM_DOCK, coins, NavPolicy.DEFAULT));
            if (r instanceof PlanResult.Planned(Route route)) {
                long owned = (long) split[0] + split[1];
                for (Route.Step step : route.steps()) {
                    if (step instanceof Route.BankStop stop) {
                        long drawn = stop.leaving().count(ItemId.COINS.id())
                                - stop.pack().count(ItemId.COINS.id());
                        long spentBefore = split[0] - stop.pack().count(ItemId.COINS.id());
                        assertTrue(
                                drawn <= split[1],
                                split[0] + "/" + split[1] + ": draws " + drawn + " of " + split[1] + " banked — "
                                        + route);
                        assertTrue(spentBefore >= 0, route.toString());
                    }
                }
                Route.Step last = route.steps().get(route.steps().size() - 1);
                long arrived = ((Route.Arrive) last).pack().count(ItemId.COINS.id());
                long spent = route.debits().getOrDefault(ItemId.COINS.id(), 0L);
                assertTrue(
                        arrived >= 100 && arrived <= owned - spent,
                        split[0] + "/" + split[1] + ": arrives " + arrived + " — " + route);
            } else {
                assertTrue(split[0] + split[1] < 130, split[0] + "/" + split[1] + ": " + r);
            }
        }
    }
}
