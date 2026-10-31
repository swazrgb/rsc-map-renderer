package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import openrsc.nav.Path;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RoutePacksTest {

    private static final Point LUMBRIDGE = Point.of(122, 647);
    private static final NavPolicy ON_FOOT =
            new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, false, Map.of(), false);

    private static ServerData env;
    private static Pathfinder pf;
    private static AccountFacts fighter;
    private static LoadoutSpec kit;
    private static Transport ship;
    private static Point karamja;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
        // The paid ship out of Port Sarim: its landing is the goal.
        ship = pf.transports().stream()
                .filter(t ->
                        t.costOverrideKey().equals(openrsc.nav.transports.NpcRoutes.SHIP_PORT_SARIM_TO_KARAMJA.name()))
                .findFirst()
                .orElseThrow();
        karamja = ship.destinationFrom(ship.entries().get(0));
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
        MutableInventory pack = new MutableInventory();
        pack.setSlot(0, new InventorySlot(0, ItemId.LOBSTER.id(), 1, false));
        MutableBank bank = new MutableBank();
        bank.setSlot(0, new BankSlot(0, ItemId.COINS.id(), 200));
        bank.setSlot(1, new BankSlot(1, ItemId.DRAGON_SWORD.id(), 1));
        fighter = new AccountFacts.Snapshot(
                env, pack.snapshot(), bank.snapshot(), stats.snapshot(), new MutableQuests().snapshot(), LUMBRIDGE);
        kit = new LoadoutSpec(List.of(new LoadoutSpec.Item(ItemId.DRAGON_SWORD, 1, 1, 1, true)), false, true);
    }

    @Test
    @DisplayName("each step holds the pack the route projects onto it")
    void eachStepHoldsItsProjectedPack() {
        PlanResult r = pf.nav().plan(new PlanRequest(fighter, LUMBRIDGE, karamja, kit, ON_FOOT));
        Route route = assertInstanceOf(PlanResult.Planned.class, r).route();
        List<Route.Step> steps = route.steps();
        int stop = -1;
        int sail = -1;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i) instanceof Route.BankStop && stop < 0) {
                stop = i;
            }
            if (steps.get(i) instanceof Route.Hop h && h.transport().equals(ship.name())) {
                sail = i;
            }
        }
        assertTrue(stop >= 0 && sail > stop, "banks, then sails: " + steps);
        long fare = ((Route.Hop) steps.get(sail)).fare().getOrDefault(ItemId.COINS.id(), 0L);
        assertTrue(fare > 0, "the ship charges a fare: " + steps.get(sail));

        for (int i = 0; i < steps.size(); i++) {
            Route.Step s = steps.get(i);
            assertEquals(1, s.pack().count(ItemId.LOBSTER.id()), "the lobster rides step " + i);
            if (i <= stop) {
                assertEquals(
                        0, s.pack().count(ItemId.DRAGON_SWORD.id()), "no sword before the stop fetches it, step " + i);
                assertEquals(0, s.pack().count(ItemId.COINS.id()), "no coins before the stop fetches them, step " + i);
            } else if (i <= sail) {
                assertTrue(
                        s.pack().isEquipped(ItemId.DRAGON_SWORD.id()),
                        "the sword is worn from the stop on, step " + i + ": " + s.pack());
                assertTrue(
                        s.pack().count(ItemId.COINS.id()) >= fare,
                        "the fare is aboard until the ship takes it, step " + i);
            } else {
                assertTrue(s.pack().isEquipped(ItemId.DRAGON_SWORD.id()), "still worn, step " + i);
                assertEquals(
                        ((Route.Hop) steps.get(sail)).pack().count(ItemId.COINS.id()) - fare,
                        s.pack().count(ItemId.COINS.id()),
                        "the ship took its fare, step " + i);
            }
        }
        Route.BankStop bank = (Route.BankStop) steps.get(stop);
        assertEquals(
                1L,
                bank.manifest().withdraw().getOrDefault(ItemId.DRAGON_SWORD, 0L),
                "the stop fetches the sword: " + bank.manifest());
        assertTrue(
                bank.manifest().withdraw().getOrDefault(ItemId.COINS, 0L) >= fare,
                "the stop fetches the fare: " + bank.manifest());
        assertTrue(bank.manifest().equip().contains(ItemId.DRAGON_SWORD), "and puts the sword on: " + bank.manifest());
        assertTrue(
                bank.manifest().deposit().isEmpty(),
                "the kit tolerates the lobster, so nothing is deposited: " + bank.manifest());
    }

    @Test
    @DisplayName("a hop's pack wears the crossing's gate, and keeps it on")
    void hopPackWearsItsGate() {
        Point entry = Point.of(659, 3302);
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
        MutableInventory pack = new MutableInventory();
        pack.setSlot(0, new InventorySlot(0, ItemId.GLARIALS_AMULET.id(), 1, false));
        AccountFacts diver = new AccountFacts.Snapshot(
                env,
                pack.snapshot(),
                new MutableBank().snapshot(),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                entry);
        PlanResult r = pf.nav().plan(new PlanRequest(diver, entry, Point.of(659, 3303), LoadoutSpec.any(), ON_FOOT));
        List<Route.Step> steps =
                assertInstanceOf(PlanResult.Planned.class, r).route().steps();
        Route.Hop doors = assertInstanceOf(Route.Hop.class, steps.get(0), "the doors first: " + steps);
        assertTrue(doors.wears().contains(ItemId.GLARIALS_AMULET.id()), "the doors want the amulet on: " + doors);
        assertTrue(doors.pack().isEquipped(ItemId.GLARIALS_AMULET.id()), "so the hop's pack wears it: " + doors.pack());
        assertTrue(
                steps.get(steps.size() - 1).pack().isEquipped(ItemId.GLARIALS_AMULET.id()),
                "and it stays on at arrival: " + steps.get(steps.size() - 1).pack());
    }

    @Test
    @DisplayName("a gate either of two gloves meets is walked through carrying both")
    void eitherGloveGateIsCrossedCarryingBoth() {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
        MutableInventory pack = new MutableInventory();
        pack.setSlot(0, new InventorySlot(0, ItemId.LEATHER_GLOVES.id(), 1, false));
        pack.setSlot(1, new InventorySlot(1, ItemId.ICE_GLOVES.id(), 1, false));
        AccountFacts account = new AccountFacts.Snapshot(
                env,
                pack.snapshot(),
                new MutableBank().snapshot(),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                Point.of(363, 3325));
        Path path = pf.findPath(363, 3325, 363, 3326, account, ON_FOOT);
        assertTrue(path != null && path.size() > 1, "a path across the gate: " + path);
        boolean crossed = false;
        for (int i = 0; i < path.size(); i++) {
            crossed |= path.isHopAt(i) && path.hopAt(i).name().startsWith("GATE_WITCHS_ELECTRIC");
        }
        assertTrue(crossed, "through the electric gate");
    }

    @Test
    @DisplayName("a gate either of two gloves meets asks for one, the one already worn")
    void eitherGloveGateNamesOneGlove() {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 99, 99, 0);
        }
        for (boolean iceOn : new boolean[] {false, true}) {
            MutableInventory pack = new MutableInventory();
            pack.setSlot(0, new InventorySlot(0, ItemId.LEATHER_GLOVES.id(), 1, false));
            pack.setSlot(1, new InventorySlot(1, ItemId.ICE_GLOVES.id(), 1, iceOn));
            AccountFacts account = new AccountFacts.Snapshot(
                    env,
                    pack.snapshot(),
                    new MutableBank().snapshot(),
                    stats.snapshot(),
                    new MutableQuests().snapshot(),
                    Point.of(363, 3325));
            PlanResult r = pf.nav()
                    .plan(new PlanRequest(
                            account, Point.of(363, 3325), Point.of(363, 3326), LoadoutSpec.any(), ON_FOOT));
            Route.Hop gate = assertInstanceOf(
                    Route.Hop.class,
                    assertInstanceOf(PlanResult.Planned.class, r)
                            .route()
                            .steps()
                            .get(0),
                    "the gate first");
            int expected = iceOn ? ItemId.ICE_GLOVES.id() : ItemId.LEATHER_GLOVES.id();
            assertEquals(java.util.Set.of(expected), gate.wears(), "ice gloves on: " + iceOn);
        }
    }
}
