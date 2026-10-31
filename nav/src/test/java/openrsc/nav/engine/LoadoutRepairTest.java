package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.container.NoteForm;
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
import openrsc.nav.transports.Webs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LoadoutRepairTest {

    private static final Point LUMBRIDGE = Point.of(122, 647);
    private static final Point VARROCK = Point.of(120, 504);
    private static final NavPolicy SPELLS_ON =
            new NavPolicy(NavPolicy.DangerAvoidance.OFF, true, false, Map.of(), false);
    private static final NavPolicy SPELLS_OFF =
            new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, false, Map.of(), false);

    private static ServerData env;
    private static Pathfinder pf;
    private static AccountFacts mage;
    private static LoadoutSpec keepTheRunes;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
        Transport teleport = pf.transports().stream()
                .filter(t -> t.name().equals(openrsc.nav.transports.TeleportSpellRoutes.VARROCK_TELEPORT.name()))
                .findFirst()
                .orElseThrow();
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 1, 1, 0);
        }
        stats.setSkill(Skill.MAGIC.ordinal(), 99, 99, 0);
        AccountFacts bare = new AccountFacts.Snapshot(
                env,
                new MutableInventory().snapshot(),
                Bank.empty(),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                LUMBRIDGE);
        MutableInventory pack = new MutableInventory();
        List<LoadoutSpec.Requirement> bands = new ArrayList<>();
        int slot = 0;
        for (Transport.Consumed c : teleport.consumes(bare)) {
            pack.setSlot(slot, new InventorySlot(slot, c.item().id(), (int) c.count(), false, false));
            slot++;
            bands.add(new LoadoutSpec.Item(c.item(), c.count(), c.count(), c.count(), false));
        }
        mage = new AccountFacts.Snapshot(
                env, pack.snapshot(), Bank.empty(), stats.snapshot(), new MutableQuests().snapshot(), LUMBRIDGE);
        keepTheRunes = new LoadoutSpec(bands);
    }

    @Test
    @DisplayName("with any loadout the teleport is the chosen chain — the breach is real")
    void theTeleportIsTheDirectChain() {
        Route r = planned(LoadoutSpec.any(), SPELLS_ON);
        assertEquals(List.of(openrsc.nav.transports.TeleportSpellRoutes.VARROCK_TELEPORT.name()), hops(r));
    }

    @Test
    @DisplayName("a fare that breaches the band with nothing banked takes the walk-around")
    void aBreachingFareIsRepairedByClosingTheHop() {
        Route walk = planned(LoadoutSpec.any(), SPELLS_OFF);
        Route repaired = planned(keepTheRunes, SPELLS_ON);
        assertEquals(List.of(), hops(repaired), "the repaired chain must not cast");
        assertTrue(repaired.debits().isEmpty(), "the repaired chain consumes nothing: " + repaired.debits());
        assertEquals(
                walk.totalCostDeci(),
                repaired.totalCostDeci(),
                "the repair is the walk the closed teleport was hiding");
    }

    @Test
    @DisplayName("a fare the loadout never names is still drawn, because the hop consumes it")
    void anUnnamedFareIsDrawnBecauseTheHopConsumesIt() {
        Point west = Point.of(81, 658);
        Point east = Point.of(101, 645);
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 1, 1, 0);
        }
        Bank banked =
                Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000), new BankSlot(1, ItemId.LOBSTER.id(), 5)));
        AccountFacts broke = new AccountFacts.Snapshot(
                env, new MutableInventory().snapshot(), banked, stats.snapshot(), new MutableQuests().snapshot(), west);
        // Names the lobster alone, so coins are surplus on arrival: the fare may still be carried to the
        // gate and spent there.
        LoadoutSpec strict =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.LOBSTER, 1, 1, 1, false)), false, false);

        PlanResult r = pf.nav().plan(new PlanRequest(broke, west, east, strict, SPELLS_OFF));
        Route route = assertInstanceOf(
                        PlanResult.Planned.class, r, "the toll's fare is in the bank and is spent before arrival")
                .route();
        assertTrue(
                hops(route).stream().anyMatch(h -> h.toLowerCase(Locale.ROOT).contains("toll")),
                "crosses the toll rather than walking the long way round it: " + hops(route));
        assertTrue(
                route.debits().containsKey(ItemId.COINS.id()),
                "the fare is drawn from the bank and spent on the way: " + route.debits());
    }

    @Test
    @DisplayName("a banked axe is withdrawn to cut the webs a melee weapon opens")
    void aBankedAxeIsGrantedForAWebCut() {
        Point from = Point.of(602, 547);
        Point mageArena = Point.of(454, 3378);
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
        Bank axeOnly =
                Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000), new BankSlot(1, ItemId.IRON_AXE.id(), 1)));
        AccountFacts account = new AccountFacts.Snapshot(
                env,
                new MutableInventory().snapshot(),
                axeOnly,
                stats.snapshot(),
                new MutableQuests().snapshot(),
                from);

        PlanResult r = pf.nav().plan(new PlanRequest(account, from, mageArena, LoadoutSpec.any(), SPELLS_OFF));
        Route route = assertInstanceOf(
                        PlanResult.Planned.class, r, "the webs want a melee weapon and the bank holds one")
                .route();
        assertTrue(
                hops(route).stream().anyMatch(LoadoutRepairTest::isWebCut),
                "the route cuts through the webs: " + hops(route));
        assertTrue(
                route.steps().stream()
                        .anyMatch(s -> s instanceof Route.BankStop b
                                && b.manifest().withdraw().containsKey(ItemId.IRON_AXE)),
                "and fetches the axe from the bank to do it: " + route.steps());
    }

    @Test
    @DisplayName("a knife held only as notes is banked and drawn back out to cut the webs")
    void aNotedKnifeIsConvertedForAWebCut() {
        Point from = Point.of(602, 547);
        Point mageArena = Point.of(454, 3378);
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
        MutableInventory pack = new MutableInventory();
        pack.setAll(List.of(new InventorySlot(0, ItemId.KNIFE.id(), 1, false, true)));
        AccountFacts account = new AccountFacts.Snapshot(
                env,
                pack.snapshot(),
                Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000))),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                from);

        PlanResult r = pf.nav().plan(new PlanRequest(account, from, mageArena, LoadoutSpec.any(), SPELLS_OFF));
        Route route = assertInstanceOf(PlanResult.Planned.class, r, "the noted knife is stock a stop converts")
                .route();
        assertTrue(
                hops(route).stream().anyMatch(LoadoutRepairTest::isWebCut),
                "the route cuts through the webs: " + hops(route));
        Route.BankStop stop = route.steps().stream()
                .filter(s ->
                        s instanceof Route.BankStop b && b.manifest().withdraw().containsKey(ItemId.KNIFE))
                .map(Route.BankStop.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stop draws the knife: " + route.steps()));
        assertEquals(0L, stop.manifest().notes().get(ItemId.KNIFE), "it banks the notes: " + stop);
        assertEquals(1, stop.leaving().count(ItemId.KNIFE.id()), "and leaves with the knife itself: " + stop);
    }

    @Test
    @DisplayName("noted cargo stays aboard; only the notes the bank cannot cover are converted")
    void notedCargoStaysAboardUnlessTheBankFallsShort() {
        LoadoutSpec cargo =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.KNIFE, 0, 50, 50, false, true)), false, true);
        Route.BankStop covered = knifeStop(
                notedKnives(50),
                Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000), new BankSlot(1, ItemId.KNIFE.id(), 3))),
                cargo);
        assertTrue(covered.manifest().notes().get(ItemId.KNIFE) == null, "no note banked: " + covered);
        assertEquals(50, covered.leaving().count(NoteForm.NOTED, ItemId.KNIFE.id()), covered.toString());
        assertEquals(1, covered.leaving().count(ItemId.KNIFE.id()), covered.toString());

        Route.BankStop converted =
                knifeStop(notedKnives(50), Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000))), cargo);
        assertEquals(49L, converted.manifest().notes().get(ItemId.KNIFE), converted.toString());
        assertEquals(49, converted.leaving().count(NoteForm.NOTED, ItemId.KNIFE.id()), converted.toString());
        assertEquals(1, converted.leaving().count(ItemId.KNIFE.id()), converted.toString());
    }

    @Test
    @DisplayName("a merged stop keeps the grant's notes conversion, or plans nothing")
    void aMergedStopKeepsTheNotesConversion() {
        LoadoutSpec shark = new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.SHARK, 1, 1, 1, false)), false, true);
        PlanResult r = plan(
                notedKnives(50),
                Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000), new BankSlot(1, ItemId.SHARK.id(), 5))),
                shark);
        if (!(r instanceof PlanResult.Planned planned)) {
            return;
        }
        Route.BankStop stop = planned.route().steps().stream()
                .filter(st -> st instanceof Route.BankStop b
                        && b.manifest().withdraw().containsKey(ItemId.KNIFE))
                .map(Route.BankStop.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "no stop draws the knife: " + planned.route().steps()));
        assertEquals(49L, stop.manifest().notes().get(ItemId.KNIFE), stop.toString());
        assertEquals(1, stop.leaving().count(ItemId.KNIFE.id()), stop.toString());
        assertEquals(49, stop.leaving().count(NoteForm.NOTED, ItemId.KNIFE.id()), stop.toString());
    }

    @Test
    @DisplayName("the arrival keeps every note its band needs, whatever a stop converts on the way")
    void theArrivalKeepsTheNotesItsBandNeeds() {
        LoadoutSpec allFifty =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.KNIFE, 50, 50, 50, false, true)), false, true);
        assertArrivesWithFiftyNotes(plan(
                notedKnives(50), Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000))), allFifty));
        LoadoutSpec fiftyAndAShark = new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(ItemId.KNIFE, 50, 50, 50, false, true),
                        new LoadoutSpec.Item(ItemId.SHARK, 1, 1, 1, false)),
                false,
                true);
        assertArrivesWithFiftyNotes(plan(
                notedKnives(50),
                Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000), new BankSlot(1, ItemId.SHARK.id(), 5))),
                fiftyAndAShark));
    }

    private static void assertArrivesWithFiftyNotes(PlanResult r) {
        if (r instanceof PlanResult.Planned planned) {
            List<Route.Step> steps = planned.route().steps();
            Route.Arrive arrive = assertInstanceOf(Route.Arrive.class, steps.get(steps.size() - 1), steps.toString());
            assertEquals(
                    50,
                    arrive.pack().count(NoteForm.NOTED, ItemId.KNIFE.id()),
                    "arrives with the cargo: " + arrive.pack().slots() + " stops "
                            + steps.stream()
                                    .filter(Route.BankStop.class::isInstance)
                                    .map(st -> ((Route.BankStop) st).manifest())
                                    .toList());
        }
    }

    private static MutableInventory notedKnives(int n) {
        MutableInventory pack = new MutableInventory();
        pack.setAll(List.of(new InventorySlot(0, ItemId.KNIFE.id(), n, false, true)));
        return pack;
    }

    private static PlanResult plan(MutableInventory pack, Bank bank, LoadoutSpec loadout) {
        Point from = Point.of(602, 547);
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
        AccountFacts account = new AccountFacts.Snapshot(
                env, pack.snapshot(), bank, stats.snapshot(), new MutableQuests().snapshot(), from);
        return pf.nav().plan(new PlanRequest(account, from, Point.of(454, 3378), loadout, SPELLS_OFF));
    }

    private static Route.BankStop knifeStop(MutableInventory pack, Bank bank, LoadoutSpec loadout) {
        Route route = assertInstanceOf(PlanResult.Planned.class, plan(pack, bank, loadout))
                .route();
        assertTrue(hops(route).stream().anyMatch(LoadoutRepairTest::isWebCut), "cuts the webs: " + hops(route));
        return route.steps().stream()
                .filter(st -> st instanceof Route.BankStop b
                        && b.manifest().withdraw().containsKey(ItemId.KNIFE))
                .map(Route.BankStop.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no stop draws the knife: " + route.steps()));
    }

    private static Route planned(LoadoutSpec loadout, NavPolicy policy) {
        PlanResult r = pf.nav().plan(new PlanRequest(mage, LUMBRIDGE, VARROCK, loadout, policy));
        return assertInstanceOf(PlanResult.Planned.class, r, "no route under " + loadout)
                .route();
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

    @Test
    @DisplayName("the withdraw stop sheds the unnamed bar")
    void theWithdrawStopShedsTheUnnamedBar() {
        Point from = Point.of(602, 547);
        Point mageArena = Point.of(454, 3378);
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
        Bank axeOnly =
                Bank.of(List.of(new BankSlot(0, ItemId.COINS.id(), 1000), new BankSlot(1, ItemId.IRON_AXE.id(), 1)));
        MutableInventory pack = new MutableInventory();
        pack.setAll(List.of(new InventorySlot(0, ItemId.BRONZE_BAR.id(), 1, false)));
        AccountFacts account = new AccountFacts.Snapshot(
                env, pack.snapshot(), axeOnly, stats.snapshot(), new MutableQuests().snapshot(), from);
        LoadoutSpec nothing = new LoadoutSpec(List.of(), false, false);

        Route route = assertInstanceOf(
                        PlanResult.Planned.class,
                        pf.nav().plan(new PlanRequest(account, from, mageArena, nothing, SPELLS_OFF)))
                .route();
        Route.BankStop withdraw = route.steps().stream()
                .filter(s ->
                        s instanceof Route.BankStop b && b.manifest().withdraw().containsKey(ItemId.IRON_AXE))
                .map(Route.BankStop.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no withdraw stop: " + route.steps()));
        assertTrue(withdraw.pack().emptySlots() > 1, "the premise: room to spare: " + withdraw);
        assertTrue(withdraw.manifest().deposit().containsKey(ItemId.BRONZE_BAR), "the bar is shed: " + withdraw);
        assertEquals(0, withdraw.leaving().count(ItemId.BRONZE_BAR.id()), withdraw.toString());
    }
}
