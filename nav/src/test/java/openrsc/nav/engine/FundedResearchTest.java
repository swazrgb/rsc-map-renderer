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
import openrsc.nav.Transport;
import openrsc.nav.eligibility.RouteContext;
import openrsc.nav.transports.Webs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundedResearchTest {

    private static final Point WEST_OF_PORT_SARIM = Point.of(329, 623);
    private static final Point KARAMJA_GATE = Point.of(428, 682);
    private static final NavPolicy AVOID_DANGER =
            new NavPolicy(NavPolicy.DangerAvoidance.ON, false, false, Map.of(), false);
    private static final NavPolicy IGNORE_DANGER =
            new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, false, Map.of(), false);

    private static ServerData env;
    private static Pathfinder pf;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
    }

    @Test
    @DisplayName("the lightest banked chain under danger avoidance is the one the loadout refuses")
    void theDangerWeightedChainCarriesTheKnifeToArrival() {
        Route tolerant = planned(levelThree(empty(), coinsAndKnife()), LoadoutSpec.any(), AVOID_DANGER);
        assertTrue(
                hops(tolerant).stream().anyMatch(FundedResearchTest::isWebCut),
                "the premise: avoiding the jungle, the banked chain cuts wilderness webs: " + hops(tolerant));
        Inventory arriving = arrival(tolerant);
        assertTrue(arriving.count(ItemId.KNIFE.id()) > 0, "and the knife the webs wanted is still aboard on arrival");
    }

    @Test
    @DisplayName("a strict arrival plans, and a knife it cuts webs with is shed before arrival")
    void aStrictArrivalShedsTheKnifeOrNeverDrawsIt() {
        AccountFacts account = levelThree(empty(), coinsAndKnife());
        for (NavPolicy policy : List.of(AVOID_DANGER, IGNORE_DANGER)) {
            Route strict = planned(account, LoadoutSpec.empty(), policy);
            assertEquals(0, arrival(strict).usedSlots(), "arrives holding nothing: " + strict.steps());
            everyHopIsOpenWithItsPack(strict, account);
            assertTrue(
                    !cutsWebs(strict) || shedsKnifeAfterWebs(strict),
                    "a knife drawn for the webs is banked past them: " + strict.steps());
        }
    }

    @Test
    @DisplayName("the knife-and-lever trip replans to itself once its first stop is done")
    void theLeverTripIsClosedUnderItsOwnFirstStop() {
        Route first = planned(levelThree(empty(), coinsAndKnife()), LoadoutSpec.empty(), AVOID_DANGER);
        if (!cutsWebs(first)) {
            return;
        }
        Route.BankStop stop = assertInstanceOf(
                Route.BankStop.class,
                first.steps().stream()
                        .filter(Route.BankStop.class::isInstance)
                        .findFirst()
                        .orElseThrow());
        MutableInventory drawn = new MutableInventory();
        int slot = 0;
        for (InventorySlot s : stop.leaving().slots()) {
            drawn.setSlot(slot, new InventorySlot(slot, s.item().id(), s.amount(), false, false));
            slot++;
        }
        List<BankSlot> left = new ArrayList<>();
        for (BankSlot b : coinsAndKnife().slots()) {
            long rest = b.amount() - stop.leaving().count(b.item().id());
            if (rest > 0) {
                left.add(new BankSlot(left.size(), b.item().id(), (int) rest));
            }
        }
        AccountFacts atBooth = levelThree(drawn.snapshot(), Bank.of(left), stop.bank());
        Route again = planned(atBooth, stop.bank(), LoadoutSpec.empty(), AVOID_DANGER);
        assertEquals(hops(first), hops(again), "the replan at the booth: " + again.steps());
        assertEquals(0, arrival(again).usedSlots(), "arrives holding nothing: " + again.steps());
    }

    private static boolean cutsWebs(Route r) {
        return hops(r).stream().anyMatch(FundedResearchTest::isWebCut);
    }

    private static boolean shedsKnifeAfterWebs(Route r) {
        int lastWeb = -1;
        for (int i = 0; i < r.steps().size(); i++) {
            if (r.steps().get(i) instanceof Route.Hop h && isWebCut(h.transport())) {
                lastWeb = i;
            }
        }
        for (int i = lastWeb + 1; i < r.steps().size(); i++) {
            if (r.steps().get(i) instanceof Route.BankStop b && b.leaving().count(ItemId.KNIFE.id()) == 0) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a carried credential the chain's gates read is never banked at the withdraw stop")
    void aCarriedCredentialTheChainReadsIsNotShedAtTheStop() {
        Point edgevilleBank = Point.of(219, 449);
        MutableInventory pack = new MutableInventory();
        pack.setSlot(0, new InventorySlot(0, ItemId.KNIFE.id(), 1, false, false));
        AccountFacts account =
                levelThree(pack.snapshot(), Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 100))), edgevilleBank);
        Route strict = planned(account, edgevilleBank, LoadoutSpec.empty(), AVOID_DANGER);
        everyHopIsOpenWithItsPack(strict, account);
        assertEquals(0, arrival(strict).usedSlots(), "arrives holding nothing: " + strict.steps());
    }

    private static Route planned(AccountFacts account, LoadoutSpec loadout, NavPolicy policy) {
        return planned(account, WEST_OF_PORT_SARIM, loadout, policy);
    }

    private static Route planned(AccountFacts account, Point from, LoadoutSpec loadout, NavPolicy policy) {
        PlanResult r = pf.nav().plan(new PlanRequest(account, from, KARAMJA_GATE, loadout, policy));
        return assertInstanceOf(PlanResult.Planned.class, r, "no route under " + loadout)
                .route();
    }

    private static void everyHopIsOpenWithItsPack(Route route, AccountFacts account) {
        for (Route.Step s : route.steps()) {
            if (!(s instanceof Route.Hop hop)) {
                continue;
            }
            Transport t = pf.transports().stream()
                    .filter(x -> x.name().equals(hop.transport()))
                    .findFirst()
                    .orElseThrow();
            RouteContext ctx = new RouteContext(account.withInventory(hop.pack()), AVOID_DANGER, env);
            assertTrue(
                    t.prereq().test(ctx),
                    hop.transport() + " is closed to the pack the route walks " + "it with: "
                            + hop.pack().slots());
        }
    }

    private static AccountFacts levelThree(Inventory pack, Bank bank) {
        return levelThree(pack, bank, WEST_OF_PORT_SARIM);
    }

    private static AccountFacts levelThree(Inventory pack, Bank bank, Point at) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 1, 1, 0);
        }
        stats.setSkill(Skill.HITS.ordinal(), 10, 10, 0);
        return new AccountFacts.Snapshot(env, pack, bank, stats.snapshot(), new MutableQuests().snapshot(), at);
    }

    private static Inventory empty() {
        return new MutableInventory().snapshot();
    }

    private static Bank coinsAndKnife() {
        return Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 100), new BankSlot(1, ItemId.KNIFE.id(), 1)));
    }

    private static Inventory arrival(Route r) {
        Route.Step last = r.steps().get(r.steps().size() - 1);
        return assertInstanceOf(Route.Arrive.class, last).pack();
    }

    private static List<String> hops(Route r) {
        List<String> out = new ArrayList<>();
        for (Route.Step s : r.steps()) {
            if (s instanceof Route.Hop h) {
                out.add(h.transport());
            }
        }
        return out;
    }

    private static boolean isWebCut(String hop) {
        String constant = hop.split("[#@]", 2)[0];
        return java.util.Arrays.stream(Webs.values()).anyMatch(w -> w.name().equals(constant));
    }
}
