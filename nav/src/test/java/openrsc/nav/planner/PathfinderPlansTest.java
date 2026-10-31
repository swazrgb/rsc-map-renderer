package openrsc.nav.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import openrsc.bot.api.cache.CacheValues;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.transports.Banks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PathfinderPlansTest {

    private static Pathfinder pathfinder;
    private static Point bank;
    private static Point nearBank;
    private static Point blocked;

    @BeforeAll
    static void build() {
        ServerData env = RealDefs.serverData();
        pathfinder = RealDefs.pathfinder();
        CollisionMap map = env.collisionMap();
        bank = Banks.tiles(env).get(0);
        // A standable tile a few steps off the booth keeps the route short, its stop at the start.
        for (int r = 2; r <= 8 && nearBank == null; r++) {
            for (int dx = -r; dx <= r && nearBank == null; dx++) {
                for (int dz = -r; dz <= r && nearBank == null; dz++) {
                    Point p = Point.of(bank.x() + dx, bank.y() + dz);
                    if (Math.abs(dx) + Math.abs(dz) >= r && !map.fullyBlocked(p.x(), p.y())) {
                        nearBank = p;
                    }
                }
            }
        }
        for (int dz = 0; dz < 64 && blocked == null; dz++) {
            for (int dx = 0; dx < 64 && blocked == null; dx++) {
                if (map.fullyBlocked(bank.x() + dx, bank.y() + dz)) {
                    blocked = Point.of(bank.x() + dx, bank.y() + dz);
                }
            }
        }
        assertNotNull(nearBank, "a standable tile near the booth");
        assertNotNull(blocked, "a tile nothing can stand on");
    }

    @Test
    void aBankedWantPutsAStopOnTheRouteAndTheStopSaysWhy() {
        int lobster = ItemId.LOBSTER.id();
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                request(
                        bank,
                        nearBank,
                        new PathfinderPlans.AccountInput(Map.of(), Map.of(), Map.of(), Map.of(lobster, 20), null),
                        new PathfinderPlans.LoadoutInput(
                                true, false, List.of(new PathfinderPlans.Want(lobster, null, 5, 5, 5, false)))));

        assertTrue(r.found(), "the walk is a few tiles: " + r.diagnosis());
        PathfinderPlans.Step stop = r.steps().stream()
                .filter(s -> s.type().equals("bank"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bank stop in " + r.steps()));
        assertEquals(
                5L,
                stop.withdraw().get(lobster),
                "withdrawn to the band's target, as an absolute pack target: " + stop.withdraw());
        assertTrue(stop.because().contains("loadout"), "the stop is there for the kit, and says so: " + stop.because());
    }

    @Test
    void aStopThatOnlyBanksJunkStillSaysWhatAskedForIt() {
        int lobster = ItemId.LOBSTER.id();
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                request(
                        bank,
                        nearBank,
                        new PathfinderPlans.AccountInput(Map.of(), Map.of(), Map.of(lobster, 20), Map.of(), null),
                        new PathfinderPlans.LoadoutInput(false, false, List.of())));

        assertTrue(r.found(), "the walk is a few tiles: " + r.diagnosis());
        PathfinderPlans.Step stop = r.steps().stream()
                .filter(s -> s.type().equals("bank"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no bank stop in " + r.steps()));
        assertTrue(
                stop.withdraw() == null || stop.withdraw().isEmpty(),
                "nothing is fetched, the stop only puts away: " + stop.withdraw());
        assertEquals(
                0L, stop.deposit().get(lobster), "banked to nothing, as an absolute pack target: " + stop.deposit());
        assertTrue(
                stop.because().contains("loadout"),
                "a deposit-only stop used to report no reason at all, so the detour read as unexplained: "
                        + stop.because());
    }

    @Test
    void aPaidFareLeavesTheAccountSoTheGateIsNotRecrossed() {
        int coins = ItemId.COINS.id();
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                request(
                        // Either side of the Al Kharid toll gate, at x 91..92.
                        Point.of(83, 646),
                        Point.of(99, 649),
                        new PathfinderPlans.AccountInput(Map.of(), Map.of(), Map.of(), Map.of(coins, 100), null),
                        new PathfinderPlans.LoadoutInput(false, false, List.of())));

        assertTrue(r.found(), "the gate is the only thing in the way: " + r.diagnosis());
        List<String> hops = r.steps().stream()
                .filter(s -> s.type().equals("hop"))
                .map(PathfinderPlans.Step::name)
                .toList();
        assertTrue(hops.size() <= 2, "crossed once, or once each way for the bank trip: " + hops);
        assertTrue(
                r.steps().size() < 300,
                "a walk across Al Kharid, not an oscillation: " + r.steps().size() + " steps");
    }

    @Test
    void aStopThatBanksJunkStillDrawsTheFareForTheGateBeyondIt() {
        int coins = ItemId.COINS.id();
        int junk = ItemId.LOBSTER.id();
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                request(
                        Point.of(81, 658),
                        Point.of(101, 645),
                        new PathfinderPlans.AccountInput(Map.of(), Map.of(), Map.of(junk, 1), Map.of(coins, 100), null),
                        new PathfinderPlans.LoadoutInput(false, false, List.of())));

        assertTrue(r.found(), "across the toll, with the fare in the bank: " + r.diagnosis());
        PathfinderPlans.Step last = r.steps().get(r.steps().size() - 1);
        assertEquals(101, last.x(), "the walk reaches the goal, not just the booth: " + last);
        assertEquals(645, last.z(), "the walk reaches the goal, not just the booth: " + last);
        assertTrue(
                r.steps().stream().anyMatch(s -> s.type().equals("hop")),
                "and crosses the gate: " + r.steps().size() + " steps, no hop among them");
    }

    @Test
    void aFullPackMakesRoomForTheBladeAndTakesItsSharkBackAtGundai() {
        int shark = ItemId.SHARK.id();
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                new PathfinderPlans.Request(
                        new PathfinderPlans.Tile(223, 105),
                        new PathfinderPlans.Tile(223, 108),
                        null,
                        new PathfinderPlans.AccountInput(
                                Map.of(),
                                Map.of(),
                                Map.of(shark, 30),
                                // An iron axe and a knife: the webs on the way want a blade.
                                Map.of(ItemId.COINS.id(), 100, 12, 1, 13, 1),
                                null),
                        new PathfinderPlans.LoadoutInput(
                                false, false, List.of(new PathfinderPlans.Want(shark, null, 30, 30, 30, false))),
                        NavPolicy.DEFAULT));

        assertTrue(r.found(), "the Mage Arena is reachable with a blade from the bank: " + r.diagnosis());
        assertEquals(30, r.steps().get(0).pack().size(), "thirty sharks are thirty slots");
        for (PathfinderPlans.Step s : r.steps()) {
            assertTrue(s.pack().size() <= 30, "a pack never holds more than 30 slots: " + s);
        }
        List<PathfinderPlans.Step> stops =
                r.steps().stream().filter(s -> s.type().equals("bank")).toList();
        assertEquals(2, stops.size(), "one stop to arm, one at Gundai to disarm: " + stops);
        assertEquals(29L, sharks(stops.get(0)), "the first stop banks a shark for the blade's slot");
        assertEquals(30, stops.get(0).pack().size(), "and leaves with a full pack, the blade in it");
        assertEquals(30L, sharks(stops.get(1)), "Gundai hands the shark back");
        assertTrue(
                stops.get(1).pack().stream().allMatch(p -> p.id() == shark),
                "and keeps the blade: the pack arrives as the loadout says, sharks only");
        assertTrue(
                r.timings().simulatedPlans() > 0 && r.timings().botPlanMs() >= 0,
                "the timings split a bot's plan from the simulation: " + r.timings());
    }

    private static long sharks(PathfinderPlans.Step s) {
        return s.pack().stream()
                .filter(p -> p.id() == ItemId.SHARK.id())
                .mapToLong(PathfinderPlans.PackItem::amount)
                .sum();
    }

    @Test
    void anEmptyBankCannotSupplyTheWantSoNothingIsPlanned() {
        int lobster = ItemId.LOBSTER.id();
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                request(
                        bank,
                        nearBank,
                        new PathfinderPlans.AccountInput(Map.of(), Map.of(), Map.of(), Map.of(), null),
                        new PathfinderPlans.LoadoutInput(
                                true, false, List.of(new PathfinderPlans.Want(lobster, null, 5, 5, 5, false)))));

        assertTrue(r.found(), "an unreachable min is relaxed, not a dead end: " + r.diagnosis());
        assertTrue(
                r.steps().stream().noneMatch(s -> s.type().equals("bank")), "and nothing is withdrawn: " + r.steps());
    }

    @Test
    void aKnownCacheValueIsTestedAndAnUnknownOnePermits() {
        String key = "barcrawl_completed";
        PathfinderAccount.Response unknown = PathfinderAccount.of(
                pathfinder,
                null,
                request(
                        bank,
                        bank,
                        new PathfinderPlans.AccountInput(Map.of(), Map.of(), Map.of(), Map.of(), null),
                        new PathfinderPlans.LoadoutInput(true, false, List.of())));
        PathfinderAccount.Response known = PathfinderAccount.of(
                pathfinder,
                null,
                request(
                        bank,
                        bank,
                        new PathfinderPlans.AccountInput(
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                new CacheValues(Map.of("barcrawl_completed", false), Map.of())),
                        new PathfinderPlans.LoadoutInput(true, false, List.of())));

        assertTrue(holds(unknown, key), "an unknown value permits");
        assertTrue(unknown.cacheAssumed().contains("barcrawl_completed"));
        assertFalse(holds(known, key), "a known false is tested");
        assertFalse(known.cacheAssumed().contains("barcrawl_completed"));
        assertTrue(known.usable().size() < unknown.usable().size(), "and the gate is closed");
    }

    private static boolean holds(PathfinderAccount.Response r, String key) {
        List<PathfinderAccount.FactRow> rows =
                r.facts().stream().filter(f -> f.fact().contains(key)).toList();
        assertEquals(1, rows.size(), "facts reading " + key + ": " + rows);
        return rows.get(0).holds();
    }

    @Test
    void aFailedPlanSaysWhichConstraintBlockedIt() {
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                request(
                        bank,
                        blocked,
                        new PathfinderPlans.AccountInput(Map.of(), Map.of(), Map.of(), Map.of(), null),
                        new PathfinderPlans.LoadoutInput(true, false, List.of())));

        assertFalse(r.found());
        assertTrue(
                r.diagnosis().contains("stand"),
                "an unstandable goal reads as 'no route' unless it is named: " + r.diagnosis());
    }

    @Test
    void theReadOutsDescribeTheAccountItPlannedAs() {
        PathfinderPlans.Response r = PathfinderPlans.plan(
                pathfinder,
                null,
                request(
                        bank,
                        nearBank,
                        new PathfinderPlans.AccountInput(Map.of("AGILITY", 40), Map.of(), Map.of(), Map.of(), null),
                        new PathfinderPlans.LoadoutInput(true, false, List.of())));

        assertEquals(3, r.account().combatLevel(), "unset skills are a fresh account's, not maxed");
        assertEquals(pathfinder.transports().size(), r.account().totalTransports());
        assertTrue(r.account().usableTransports() > 0);
        assertTrue(
                r.account().usableTransports() < r.account().totalTransports(),
                "a fresh account cannot use every gate");
        assertEquals(0, r.account().wildernessLevel(), "the booth is not in the wilderness");
        assertNotNull(r.timings());
    }

    @Test
    void theAccountBreakdownAgreesWithThePlansCount() {
        PathfinderPlans.Request req = request(
                bank,
                nearBank,
                new PathfinderPlans.AccountInput(Map.of("AGILITY", 40), Map.of(), Map.of(), Map.of(), null),
                new PathfinderPlans.LoadoutInput(true, false, List.of()));
        PathfinderPlans.Response plan = PathfinderPlans.plan(pathfinder, null, req);
        PathfinderAccount.Response a = PathfinderAccount.of(pathfinder, null, req);

        assertEquals(
                plan.account().usableTransports(),
                a.usable().size(),
                "the breakdown lists exactly the transports the header counts");
        assertEquals(plan.account().totalTransports(), a.total());
        assertTrue(a.facts().stream().anyMatch(f -> !f.holds()), "a fresh account fails some fact");
        assertTrue(a.facts().stream().anyMatch(PathfinderAccount.FactRow::holds));
        assertTrue(a.rules().stream().anyMatch(r -> !r.passes()), "and some gate rule");
        assertTrue(
                a.usable().stream().anyMatch(o -> o.x() != null),
                "a usable transport has a tile to show: " + a.usable().subList(0, 3));
    }

    private static PathfinderPlans.Request request(
            Point from, Point to, PathfinderPlans.AccountInput account, PathfinderPlans.LoadoutInput loadout) {
        return new PathfinderPlans.Request(
                new PathfinderPlans.Tile(from.x(), from.y()),
                new PathfinderPlans.Tile(to.x(), to.y()),
                null,
                account,
                loadout,
                NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF));
    }
}
