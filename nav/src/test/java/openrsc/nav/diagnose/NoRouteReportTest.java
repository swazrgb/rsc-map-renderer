package openrsc.nav.diagnose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemRef;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.planner.InputsProbeBot;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoRouteReportTest {

    // West of the Underground Pass, and its far end: the way swings from stalactites on a rope and
    // crosses the old bridge on arrows.
    private static final Point OUTSIDE = Point.of(680, 574);

    private static final Point FAR_SIDE = Point.of(739, 608);

    private static final int ROPE = 237;
    private static final int SHORTBOW = 189;
    private static final int ARROWS = 11;

    private static final int KNIFE = 13;
    private static final int BRONZE_SHORT_SWORD = 66;

    private static ServerData env;
    private static Pathfinder pf;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
    }

    @Test
    @DisplayName("an arrival that sheds a carried credential names the item, the hop and the fix")
    void theShedVerdictNamesTheRopeAndTheStalactite() {
        Diagnosis d = explain(arriving(SHORTBOW));

        assertEquals(Diagnosis.Kind.SHED, d.kind(), d.line());
        Diagnosis.Blocker rope = blockerFor(d, ROPE);
        assertTrue(rope.what().contains("Rope"), rope.what());
        assertEquals(List.of(ROPE), rope.items(), "and the UI can find the rope's row from it");
        assertNotNull(rope.transport(), "the hop the rope is the credential for: " + rope.why());
        assertTrue(rope.transport().contains("_STALACTITE_"), rope.transport());
        assertEquals(
                rope.transport() + " needs a Rope, but the loadout wants none",
                rope.why(),
                "a CARRIED credential is not a fare, and the difference is the whole fix");
        assertEquals(rope.why(), d.summary(), "one blocker: its sentence is the verdict");
        assertTrue(rope.fix().contains("arrive count of 1"), rope.fix());
    }

    @Test
    @DisplayName("the narrowing names the rope alone — the arrows the bridge burns are not a blocker")
    void theArrowTheRouteSpendsIsNotBlamed() {
        Diagnosis d = explain(arriving(SHORTBOW));

        assertEquals(
                1,
                blockers(d).size(),
                "the arrows are SPENT before arrival, so shedding it costs nothing and naming it sends"
                        + " the reader to the wrong row: " + d.line());
        assertEquals(List.of(ROPE), blockers(d).get(0).items(), d.line());
    }

    @Test
    @DisplayName("the change the verdict asks for is the change that plans")
    void theFixTheVerdictNamesIsTheOneThatPlans() {
        assertFalse(routes(arriving(SHORTBOW)), "the premise: shedding the rope strands the trip");
        assertTrue(
                routes(arriving(SHORTBOW, ROPE)),
                "and the rope's arrive count, which is what the verdict asked to raise, is the fix");
    }

    @Test
    @DisplayName("a min the route's own fares empty is named as such, not merely as unmet")
    void aBandTheFaresEmptyNamesTheFare() {
        Diagnosis d = explain(arriving(SHORTBOW, ROPE, ARROWS));

        assertEquals(Diagnosis.Kind.LOADOUT, d.kind(), d.line());
        Diagnosis.Blocker b = blockerFor(d, ARROWS);
        assertTrue(
                b.what().contains("pack ") && b.what().contains("bank "),
                "the stock split says which side it sits on: " + b.what());
        assertTrue(
                b.why().contains(" spends ") && b.why().contains("fewer than the loadout's 1"),
                "the band reads as met; only the fare explains it: " + b.why());
        assertFalse(b.fix().isBlank(), "and it still says what to change");
    }

    @Test
    @DisplayName("a loadout failing for two reasons at once is fixed by answering both")
    void aFixAnswersEveryReasonTheLoadoutFails() {
        LoadoutSpec twoReasons = new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(refOf(SHORTBOW), 1, 1, 1, false),
                        new LoadoutSpec.Item(refOf(ROPE), 0, 0, 0, false),
                        new LoadoutSpec.Item(refOf(ARROWS), 1, 1, 1, false)),
                false,
                false);
        assertFalse(routes(twoReasons), "the premise: it does not plan");

        Diagnosis d = explain(twoReasons);

        assertEquals(Diagnosis.Kind.LOADOUT, d.kind(), d.line());
        for (Diagnosis.Fix f : d.fixes()) {
            List<Integer> named =
                    f.blockers().stream().flatMap(b -> b.items().stream()).toList();
            assertTrue(named.contains(ROPE) && named.contains(ARROWS), "every fix answers both reasons: " + d.line());
            assertFalse(named.contains(SHORTBOW), "and nothing else: " + d.line());
        }
        assertFalse(d.fixes().isEmpty(), d.line());
    }

    @Test
    @DisplayName("a shed two credentials can each fix lists both, one per fix")
    void aShedEitherCredentialFixesListsBoth() {
        // The webs on the way are cut with a knife or a melee weapon, so either is a fix.
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L30
        Point wildernessPastWebs = Point.of(224, 192);
        Map<Integer, Integer> pack = new LinkedHashMap<>();
        pack.put(KNIFE, 1);
        pack.put(BRONZE_SHORT_SWORD, 1);
        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                maxedCarrying(pack).at(OUTSIDE.x(), OUTSIDE.y()),
                env.collisionMap(),
                OUTSIDE,
                wildernessPastWebs,
                LoadoutSpec.empty(),
                policy(),
                true);

        assertEquals(Diagnosis.Kind.SHED, d.kind(), d.line());
        assertEquals(2, d.fixes().size(), d.line());
        List<List<Integer>> kept = d.fixes().stream()
                .map(f -> f.blockers().stream().flatMap(b -> b.items().stream()).toList())
                .toList();
        assertTrue(
                kept.contains(List.of(KNIFE)) && kept.contains(List.of(BRONZE_SHORT_SWORD)),
                "keep the knife, or keep the sword: " + d.line());
    }

    @Test
    @DisplayName("a credential the route withdraws is named like one it carries")
    void aWithdrawnCredentialIsNamed() {
        Point wildernessPastWebs = Point.of(224, 192);
        AccountFacts banked = maxed(Map.of(), Map.of(KNIFE, 1));
        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                banked.at(OUTSIDE.x(), OUTSIDE.y()),
                env.collisionMap(),
                OUTSIDE,
                wildernessPastWebs,
                LoadoutSpec.empty(),
                policy(),
                true);

        assertEquals(Diagnosis.Kind.SHED, d.kind(), d.line());
        Diagnosis.Blocker knife = blockerFor(d, KNIFE);
        assertNotNull(knife.transport(), "the web it cuts: " + d.line());
        assertTrue(knife.transport().startsWith("WEB@"), knife.transport());

        LoadoutSpec keepKnife =
                new LoadoutSpec(List.of(new LoadoutSpec.Item(refOf(KNIFE), 0, 0, Long.MAX_VALUE, false)), false, false);
        assertTrue(
                pf.nav()
                                .plan(new openrsc.nav.PlanRequest(
                                        banked.at(OUTSIDE.x(), OUTSIDE.y()),
                                        OUTSIDE,
                                        wildernessPastWebs,
                                        keepKnife,
                                        policy()))
                        instanceof openrsc.nav.PlanResult.Planned,
                "arriving with whatever knife the trip leaves routes");
    }

    @Test
    @DisplayName("hops behind one gate are one fix, however many of them the way crosses")
    void hopsBehindOneGateAreOneFix() {
        // The Karamja jungle: hundreds of cuts behind a few gates.
        Point pastTheJungle = Point.of(384, 896);
        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                account(3).at(OUTSIDE.x(), OUTSIDE.y()),
                env.collisionMap(),
                OUTSIDE,
                pastTheJungle,
                LoadoutSpec.empty(),
                policy(),
                true);

        assertEquals(Diagnosis.Kind.ELIGIBILITY, d.kind(), d.line());
        assertTrue(d.fixes().size() <= 3, "a handful of gates, not every path: " + d.line());
        assertTrue(
                d.fixes().stream()
                        .allMatch(f -> f.blockers().size() == 1
                                && f.blockers().get(0).what().contains("more behind the same gate")),
                "each fix one gate, counting the cuts behind it: " + d.line());
    }

    @Test
    @DisplayName("an ask that plans is told so, rather than narrowed into an empty verdict")
    void anAskThatPlansIsNotGivenAVerdict() {
        LoadoutSpec twoRopes = new LoadoutSpec(
                List.of(
                        new LoadoutSpec.Item(refOf(SHORTBOW), 1, 1, 1, false),
                        new LoadoutSpec.Item(refOf(ROPE), 2, 2, 2, false)),
                false,
                false);
        assertTrue(routes(twoRopes), "the premise: the engine sources the second rope");

        Diagnosis d = explain(twoRopes);

        assertEquals(Diagnosis.Kind.UNDIAGNOSED, d.kind(), d.line());
        assertTrue(d.summary().contains("downstream of the route"), d.summary());
    }

    @Test
    @DisplayName("an unbridged component reads as the map, and names both components")
    void anUnbridgedComponentNamesBothComponents() {
        Point stranded = strandedTile();
        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                account(3).at(OUTSIDE.x(), OUTSIDE.y()),
                env.collisionMap(),
                OUTSIDE,
                stranded,
                LoadoutSpec.any(),
                policy(),
                true);

        assertEquals(Diagnosis.Kind.GEOGRAPHY, d.kind(), d.line());
        assertTrue(d.summary().contains("walk component "), d.summary());
    }

    @Test
    @DisplayName("a closed hop is named with the gate it fails, from the planner's own lookup")
    void aClosedHopCarriesItsGateText() {
        // The Dig Site winch, which lowers you into the cavern once the quest is done.
        Point winch = Point.of(14, 506);
        Point cavern = Point.of(19, 3385);
        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                freshAccount().at(winch.x(), winch.y()),
                env.collisionMap(),
                winch,
                cavern,
                LoadoutSpec.any(),
                policy(),
                true);

        assertEquals(Diagnosis.Kind.ELIGIBILITY, d.kind(), d.line());
        assertFalse(blockers(d).isEmpty(), d.line());
        Diagnosis.Blocker b = blockers(d).get(0);
        assertNotNull(b.transport(), "the hop: " + d.line());
        assertTrue(
                b.why().startsWith(b.transport() + " needs "),
                "the hop, and what of its gate is unmet, in the gate's own words: " + b.why());
        assertFalse(b.fix().equals("satisfy "), "with something to satisfy named: " + b.fix());
    }

    @Test
    @DisplayName("a closed hop that only saves time is not named beside the one in the way")
    void aClosedShortcutIsNotBlamedBesideTheGate() {
        // With every gate lifted the cheapest route teleports to Draynor on a dragonstone amulet the
        // account lacks, but only the rockslide is in the way: Draynor is a walk.
        Point lumbridge = Point.of(122, 647);
        Point pastTheRockslide = Point.of(432, 432);
        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                freshAccount().at(lumbridge.x(), lumbridge.y()),
                env.collisionMap(),
                lumbridge,
                pastTheRockslide,
                LoadoutSpec.any(),
                policy(),
                true);

        assertEquals(Diagnosis.Kind.ELIGIBILITY, d.kind(), d.line());
        assertEquals(1, blockers(d).size(), d.line());
        assertEquals(
                "ROCKSLIDE_MINE_THROUGH@426_438#425_438", blockers(d).get(0).transport(), d.line());
        assertFalse(
                hops(d).stream().anyMatch(h -> h.startsWith("DRAGONSTONE_AMULET_")),
                "and the route it shows is the one the account could take: " + hops(d));
    }

    @Test
    @DisplayName("a lifted gate never widens the policy: teleport opt-ins and prohibits hold")
    void aLiftNeverWidensThePolicy() {
        Point lumbridge = Point.of(122, 647);
        Point karamja = Point.of(336, 816);
        openrsc.nav.TransportRef amulet = () -> "DRAGONSTONE_AMULET_KARAMJA";
        NavPolicy jewelleryOn = new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, true, Map.of(), false);
        NavPolicy jewelleryOff = new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, false, Map.of(), false);
        assertTrue(
                freshPlans(lumbridge, karamja, jewelleryOn.lift(amulet)),
                "the premise: lifting the amulet's gate routes when the policy allows jewellery");
        assertFalse(
                freshPlans(lumbridge, karamja, jewelleryOff.lift(amulet)),
                "a lift must not open a teleport the policy has not opted into");
        assertFalse(
                freshPlans(lumbridge, karamja, jewelleryOn.prohibit(amulet).lift(amulet)),
                "nor a transport the policy prohibits");

        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                freshAccount().at(lumbridge.x(), lumbridge.y()),
                env.collisionMap(),
                lumbridge,
                karamja,
                LoadoutSpec.any(),
                jewelleryOff,
                true);
        for (Diagnosis.Fix f : d.fixes()) {
            boolean namesAmulet =
                    f.blockers().stream().anyMatch(b -> "DRAGONSTONE_AMULET_KARAMJA".equals(b.transport()));
            boolean allowed =
                    f.blockers().stream().anyMatch(b -> b.what().equals("teleport jewellery off in the policy"));
            assertTrue(!namesAmulet || allowed, "the amulet named without the knob that opens it: " + f);
            assertTrue(
                    !f.hops().contains("DRAGONSTONE_AMULET_KARAMJA") || allowed,
                    "a route through the amulet under a policy that forbids it: " + f);
        }
    }

    @Test
    @DisplayName("a trip only the policy's own prohibit shuts is the policy's verdict")
    void aProhibitTheAccountCouldCrossIsThePolicysVerdict() {
        Point lumbridge = Point.of(122, 647);
        AccountFacts fresh = freshAccount().at(lumbridge.x(), lumbridge.y());
        NavPolicy base = new NavPolicy(NavPolicy.DangerAvoidance.ON, false, false, Map.of(), false);
        // Found on the map: the first trip from Lumbridge that a prohibit on one of its own hops strands.
        for (int y = 0; y < env.collisionMap().height(); y += 48) {
            for (int x = 0; x < env.collisionMap().width(); x += 48) {
                Point to = Point.of(x, y);
                if (env.collisionMap().fullyBlocked(x, y)
                        || !(pf.nav().plan(new openrsc.nav.PlanRequest(fresh, lumbridge, to, LoadoutSpec.any(), base))
                                instanceof openrsc.nav.PlanResult.Planned(openrsc.nav.Route r))) {
                    continue;
                }
                for (openrsc.nav.Route.Step s : r.steps()) {
                    if (!(s instanceof openrsc.nav.Route.Hop h)) {
                        continue;
                    }
                    NavPolicy fenced = base.prohibit(h::transport);
                    if (freshPlans(lumbridge, to, fenced)) {
                        continue;
                    }
                    Diagnosis d = NoRouteReport.explain(
                            pf.nav(), fresh, env.collisionMap(), lumbridge, to, LoadoutSpec.any(), fenced, true);
                    assertEquals(Diagnosis.Kind.POLICY, d.kind(), d.line());
                    Diagnosis.Fix first = d.fixes().get(0);
                    assertEquals(1, first.blockers().size(), d.line());
                    assertEquals(h.transport(), first.blockers().get(0).transport(), d.line());
                    assertTrue(first.summary().contains("the account can make this trip"), first.summary());
                    return;
                }
            }
        }
        throw new IllegalStateException("no single prohibit strands any trip from Lumbridge");
    }

    @Test
    @DisplayName("a trip with several ways in lists every fix, each with nothing to spare")
    void everyFixIsListed() {
        Point lumbridge = Point.of(122, 647);
        Point karamja = Point.of(336, 816);
        NavPolicy jewelleryOff = new NavPolicy(NavPolicy.DangerAvoidance.ON, false, false, Map.of(), false);
        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                freshAccount().at(lumbridge.x(), lumbridge.y()),
                env.collisionMap(),
                lumbridge,
                karamja,
                LoadoutSpec.any(),
                jewelleryOff,
                true);

        assertTrue(d.fixes().size() > 1, d.line());
        assertTrue(
                d.fixes().stream()
                        .anyMatch(f -> f.kind() == Diagnosis.Kind.ELIGIBILITY
                                && f.blockers().size() == 1
                                && f.blockers().get(0).why().contains("The Grand Tree complete")
                                && f.blockers()
                                        .get(0)
                                        .quests()
                                        .equals(List.of(openrsc.bot.api.quest.QuestId.GRAND_TREE.id()))
                                && f.hops().contains("GLIDER_TO_KARAMJA")),
                "the Grand Tree gliders' gate, alone: " + d.line());
        Diagnosis.Fix pair = d.fixes().stream()
                .filter(f -> f.blockers().stream().anyMatch(b -> "DRAGONSTONE_AMULET_KARAMJA".equals(b.transport())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no amulet fix: " + d.line()));
        assertEquals(Diagnosis.Kind.POLICY, pair.kind(), d.line());
        assertEquals(2, pair.blockers().size(), "the knob and the gate, nothing else: " + pair);
        assertTrue(pair.summary().contains("also needs a gate"), pair.summary());
        for (int i = 0; i < d.fixes().size(); i++) {
            for (int j = 0; j < d.fixes().size(); j++) {
                List<String> a = whats(d.fixes().get(i));
                List<String> b = whats(d.fixes().get(j));
                assertTrue(
                        i == j || !b.containsAll(a),
                        "a fix that contains another is not minimal: " + a + " within " + b);
            }
        }
    }

    private static List<String> whats(Diagnosis.Fix f) {
        return f.blockers().stream().map(Diagnosis.Blocker::what).toList();
    }

    private static boolean freshPlans(Point from, Point to, NavPolicy policy) {
        return pf.nav()
                        .plan(new openrsc.nav.PlanRequest(
                                freshAccount().at(from.x(), from.y()), from, to, LoadoutSpec.any(), policy))
                instanceof openrsc.nav.PlanResult.Planned;
    }

    @Test
    @DisplayName("no verdict quotes a raw item or quest id at the reader")
    void verdictsNameThingsRatherThanNumberThem() {
        Point winch = Point.of(14, 506);
        Point cavern = Point.of(19, 3385);
        List<Diagnosis> all = List.of(
                explain(arriving(SHORTBOW)),
                explain(arriving(SHORTBOW, ROPE, ARROWS)),
                NoRouteReport.explain(
                        pf.nav(),
                        freshAccount().at(winch.x(), winch.y()),
                        env.collisionMap(),
                        winch,
                        cavern,
                        LoadoutSpec.any(),
                        policy(),
                        true));

        for (Diagnosis d : all) {
            String line = d.line() + " | " + d.summary();
            for (Diagnosis.Blocker b : blockers(d)) {
                line += " | " + b.what() + " | " + b.why() + " | " + b.fix();
            }
            assertFalse(line.contains("item#"), "a raw item id reached the reader: " + line);
            assertFalse(line.contains("quest#"), "a raw quest id reached the reader: " + line);
            assertFalse(line.contains("?"), "an unnamed id reached the reader as '?': " + line);
        }
    }

    @Test
    @DisplayName("an unstandable destination is not reported as a routing failure")
    void aBlockedDestinationIsItsOwnVerdict() {
        Point wall = blockedTileNear(FAR_SIDE);
        Diagnosis d = NoRouteReport.explain(
                pf.nav(),
                account(3).at(OUTSIDE.x(), OUTSIDE.y()),
                env.collisionMap(),
                OUTSIDE,
                wall,
                LoadoutSpec.any(),
                policy(),
                true);
        assertEquals(Diagnosis.Kind.DESTINATION, d.kind(), d.line());
    }

    private static Diagnosis explain(LoadoutSpec loadout) {
        return NoRouteReport.explain(
                pf.nav(),
                account(3).at(OUTSIDE.x(), OUTSIDE.y()),
                env.collisionMap(),
                OUTSIDE,
                FAR_SIDE,
                loadout,
                policy(),
                true);
    }

    private static List<Diagnosis.Blocker> blockers(Diagnosis d) {
        return d.fixes().isEmpty() ? List.of() : d.fixes().get(0).blockers();
    }

    private static List<String> hops(Diagnosis d) {
        return d.fixes().isEmpty() ? List.of() : d.fixes().get(0).hops();
    }

    private static Diagnosis.Blocker blockerFor(Diagnosis d, int item) {
        for (Diagnosis.Blocker b : blockers(d)) {
            if (b.items().contains(item)) {
                return b;
            }
        }
        throw new AssertionError("no blocker names item " + item + " in: " + d.line());
    }

    private static ItemRef refOf(int id) {
        return () -> id;
    }

    private static boolean routes(LoadoutSpec loadout) {
        return pf.nav()
                        .plan(new openrsc.nav.PlanRequest(
                                account(3).at(OUTSIDE.x(), OUTSIDE.y()), OUTSIDE, FAR_SIDE, loadout, policy()))
                instanceof openrsc.nav.PlanResult.Planned;
    }

    private static NavPolicy policy() {
        return new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, true, Map.of(), false);
    }

    private static AccountFacts account(int arrows) {
        Map<Integer, Integer> inventory = new LinkedHashMap<>();
        inventory.put(SHORTBOW, 1);
        inventory.put(ROPE, 1);
        if (arrows > 0) {
            inventory.put(ARROWS, arrows);
        }
        return maxedCarrying(inventory);
    }

    private static AccountFacts maxedCarrying(Map<Integer, Integer> inventory) {
        return maxed(inventory, Map.of());
    }

    private static AccountFacts maxed(Map<Integer, Integer> inventory, Map<Integer, Integer> bank) {
        Map<Skill, Integer> skills = new LinkedHashMap<>();
        for (Skill s : Skill.values()) {
            skills.put(s, 99);
        }
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (int q = 0; q < 50; q++) {
            quests.put(q, -1);
        }
        return new InputsProbeBot(env, skills, inventory, Set.of(), bank, quests, CacheValues.EMPTY);
    }

    private static AccountFacts freshAccount() {
        Map<Skill, Integer> skills = new LinkedHashMap<>();
        for (Skill s : Skill.values()) {
            skills.put(s, 1);
        }
        return new InputsProbeBot(env, skills, Map.of(), Set.of(), Map.of(), Map.of(), CacheValues.EMPTY);
    }

    private static LoadoutSpec arriving(int... ids) {
        List<LoadoutSpec.Requirement> reqs = new ArrayList<>(ids.length);
        for (int id : ids) {
            reqs.add(new LoadoutSpec.Item(refOf(id), 1, 1, 1, false));
        }
        return new LoadoutSpec(reqs, false, false);
    }

    private static Point strandedTile() {
        AccountFacts from = account(3).at(OUTSIDE.x(), OUTSIDE.y());
        NavPolicy lifted = policy().withAllEligible();
        for (int y = 0; y < env.collisionMap().height(); y += 16) {
            for (int x = 0; x < env.collisionMap().width(); x += 16) {
                if (env.collisionMap().fullyBlocked(x, y)) {
                    continue;
                }
                Point p = Point.of(x, y);
                if (pf.nav().plan(new openrsc.nav.PlanRequest(from, OUTSIDE, p, LoadoutSpec.any(), lifted))
                        instanceof openrsc.nav.PlanResult.NoRoute) {
                    return p;
                }
            }
        }
        throw new IllegalStateException("every sampled tile routes — no stranded component to test");
    }

    private static Point blockedTileNear(Point near) {
        for (int r = 1; r < 40; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    int x = near.x() + dx;
                    int y = near.y() + dy;
                    if (env.collisionMap().inBounds(x, y) && env.collisionMap().fullyBlocked(x, y)) {
                        return Point.of(x, y);
                    }
                }
            }
        }
        throw new IllegalStateException("no blocked tile near " + near);
    }
}
