package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.BankArea;
import openrsc.gamedata.data.Location;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import openrsc.nav.Route;
import openrsc.nav.TravelTime;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AreaGoalTest {

    private static final Rect GRAND_TREE =
            BankArea.GRAND_TREE_SECOND_FLOOR.boxes().getFirst();

    private static Route plan(Pathfinder pf, AccountFacts facts, Point from, Area to) {
        PlanResult r = pf.nav().plan(new PlanRequest(facts, from, to, LoadoutSpec.any(), NavPolicy.DEFAULT));
        return assertInstanceOf(PlanResult.Planned.class, r, "plans from " + from + " into " + to)
                .route();
    }

    @Test
    @DisplayName("an area whose centre is an island plans into its reachable part")
    void theGrandTreeBankPlansFromTheMagicDoor() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        CollisionMap map = env.collisionMap();
        Point centre = Point.of(716, 1401);
        assertTrue(!map.fullyBlocked(centre.x(), centre.y()), "the centre is standable");
        assertTrue(
                map.componentId(centre.x(), centre.y())
                        != map.componentId(Location.BANK_GRAND_TREE.x(), Location.BANK_GRAND_TREE.z()),
                "and walled off from the bank floor: the area spans two walk components");
        for (Point from : List.of(Point.of(708, 1395), Point.of(713, 1401))) {
            AccountFacts facts = NavProbe.onFootFacts(env, from.x(), from.y());
            Route route = plan(pf, facts, from, GRAND_TREE);
            TravelTime estimate = pf.nav().estimate(facts, from, GRAND_TREE, NavPolicy.DEFAULT);
            assertTrue(estimate.isReachable(), "priced from " + from);
            assertEquals(route.totalCostDeci(), estimate.deci(), "plan and estimate agree from " + from);
            assertNotNull(pf.nav().walk(from, GRAND_TREE), "and walked from " + from);
        }
    }

    @Test
    @DisplayName("every bank area plans from just outside it, no dearer than its stand tile")
    void everyBankAreaPlansFromOutside() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        CollisionMap map = env.collisionMap();
        List<String> failures = new ArrayList<>();
        for (Location bank : Location.BANKS) {
            BankArea area = bank.bankArea();
            if (area == null) {
                continue;
            }
            Point from = nearestOutside(map, bank, area);
            if (from == null) {
                continue;
            }
            AccountFacts facts = NavProbe.onFootFacts(env, from.x(), from.y());
            for (Rect box : area.boxes()) {
                PlanResult r = pf.nav().plan(new PlanRequest(facts, from, box, LoadoutSpec.any(), NavPolicy.DEFAULT));
                if (!(r instanceof PlanResult.Planned)) {
                    if (box.contains(bank.x(), bank.z())) {
                        failures.add(bank + " " + box + " from " + from + ": " + r);
                    }
                    continue;
                }
                if (box.contains(bank.x(), bank.z())) {
                    int toArea = pf.nav()
                            .estimate(facts, from, box, NavPolicy.DEFAULT)
                            .deci();
                    int toStand = pf.nav()
                            .estimate(facts, from, bank.point(), NavPolicy.DEFAULT)
                            .deci();
                    if (toArea < 0 || toArea > toStand) {
                        failures.add(bank + " from " + from + ": area " + toArea + " vs stand tile " + toStand);
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    @Test
    @DisplayName("an area is priced to its nearest tile, not its centre")
    void aLongAreaIsEnteredAtItsNearEnd() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        Point from = Point.of(126, 670);
        Rect field = new Rect(128, 668, 160, 672);
        AccountFacts facts = NavProbe.onFootFacts(env, from.x(), from.y());
        int toArea = pf.nav().estimate(facts, from, field, NavPolicy.DEFAULT).deci();
        int toNearEnd = pf.nav()
                .estimate(facts, from, Point.of(128, 670), NavPolicy.DEFAULT)
                .deci();
        int toCentre = pf.nav()
                .estimate(facts, from, Point.of(144, 670), NavPolicy.DEFAULT)
                .deci();
        assertTrue(toArea >= 0 && toArea <= toNearEnd, "area " + toArea + " vs its near end " + toNearEnd);
        assertTrue(toArea < toCentre, "area " + toArea + " vs its centre " + toCentre);
        Route route = plan(pf, facts, from, field);
        assertEquals(toArea, route.totalCostDeci(), "the plan agrees");
        Planner.Leg walk = pf.nav().walk(from, field);
        assertNotNull(walk);
        assertTrue(walk.deci() <= toNearEnd, "and so does the walk: " + walk);
    }

    @Test
    @DisplayName("a walk into an area is its shortest walk, even one that swings wide")
    void aWalkThatSwingsWideIsStillTheShortest() {
        Pathfinder pf = RealDefs.cachedPathfinder();
        record Case(Point from, Rect area, int ticks) {}
        for (Case c : List.of(
                new Case(Point.of(589, 525), new Rect(571, 473, 577, 500), 63),
                new Case(Point.of(40, 533), new Rect(50, 535, 69, 539), 79),
                new Case(Point.of(143, 667), new Rect(142, 683, 151, 687), 65))) {
            Planner.Leg walk = pf.nav().walk(c.from(), c.area());
            assertNotNull(walk, "walks from " + c.from());
            assertEquals(c.ticks() * 10, walk.deci(), "from " + c.from() + " into " + c.area() + ": " + walk);
        }
    }

    private static Point nearestOutside(CollisionMap map, Location bank, BankArea area) {
        int stand = map.componentId(bank.x(), bank.z());
        Point best = null;
        int bestD = Integer.MAX_VALUE;
        for (int dz = -8; dz <= 8; dz++) {
            for (int dx = -10; dx <= 10; dx++) {
                int x = bank.x() + dx, z = bank.z() + dz;
                if (map.fullyBlocked(x, z) || map.componentId(x, z) != stand || area.contains(x, z, 64)) {
                    continue;
                }
                int d = Math.max(Math.abs(dx), Math.abs(dz));
                if (d < bestD) {
                    bestD = d;
                    best = Point.of(x, z);
                }
            }
        }
        return best;
    }
}
