package openrsc.nav.planner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.stats.Skill;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlanDiagnosisTest {

    private static final PathfinderPlans.Tile OUTSIDE = new PathfinderPlans.Tile(680, 574);
    private static final PathfinderPlans.Tile FAR_SIDE = new PathfinderPlans.Tile(739, 608);

    private static final int ROPE = 237;
    // A bow and three arrows: the old bridge on the way wants them.
    private static final int SHORTBOW = 189;
    private static final int ARROWS = 11;

    private static Pathfinder pf;

    @BeforeAll
    static void load() {
        RealDefs.serverData();

        pf = RealDefs.pathfinder();
    }

    @Test
    @DisplayName("the shared link plans when the arrival keeps the rope")
    void theLinkWithTheRopeKept() {
        PathfinderPlans.Response r = plan(1);
        assertTrue(r.found(), () -> "expected a plan, got: " + r.diagnosis());
    }

    @Test
    @DisplayName("and at arrive=0 the panel is told the item, the hop and the edit to make")
    void theLinkWithTheRopeShed() {
        PathfinderPlans.Response r = plan(0);

        assertFalse(r.found(), "the premise: shedding the rope strands the trip");
        PathfinderPlans.Why why = r.why();
        assertNotNull(why, "a failed plan must carry the structured verdict, not only a sentence");
        assertEquals("SHED", why.kind(), why.summary());
        assertEquals(1, why.fixes().size(), "a shed has one fix: " + why.fixes());
        PathfinderPlans.Fix fix = why.fixes().get(0);
        assertFalse(fix.blockers().isEmpty(), why.summary());

        PathfinderPlans.Blocker b = fix.blockers().stream()
                .filter(x -> x.items().contains(ROPE))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no blocker names the rope: " + fix.blockers()));
        assertTrue(b.what().contains("Rope"), b.what());
        assertNotNull(b.transport(), "the hop the rope is the credential for: " + b.why());
        assertTrue(b.transport().contains("_STALACTITE_"), b.transport());
        assertTrue(
                b.fix().contains("arrive count of 1"),
                "the fix is the edit the editor offers a column for: " + b.fix());
        assertFalse(fix.hops().isEmpty(), "and the route it tried, for context");
    }

    private static PathfinderPlans.Response plan(int rope) {
        Map<String, Integer> skills = new LinkedHashMap<>();
        for (Skill s : Skill.values()) {
            skills.put(s.name(), 99);
        }
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (int q = 0; q < 50; q++) {
            quests.put(q, -1);
        }
        Map<Integer, Integer> inventory = new LinkedHashMap<>();
        inventory.put(SHORTBOW, 1);
        inventory.put(ARROWS, 3);
        inventory.put(ROPE, 1);
        PathfinderPlans.AccountInput account =
                new PathfinderPlans.AccountInput(skills, quests, inventory, Map.of(), CacheValues.EMPTY);

        // As the editor sends it: an arrive count of 0 is no want at all, and nothing unnamed is kept,
        // so at 0 the rope has to be banked.
        List<PathfinderPlans.Want> wants =
                rope > 0 ? List.of(want(SHORTBOW, 1), want(ROPE, rope)) : List.of(want(SHORTBOW, 1));
        PathfinderPlans.LoadoutInput loadout = new PathfinderPlans.LoadoutInput(false, false, wants);

        NavPolicy policy = new NavPolicy(NavPolicy.DangerAvoidance.OFF, false, true, Map.of(), false);
        return PathfinderPlans.plan(
                pf, null, new PathfinderPlans.Request(OUTSIDE, FAR_SIDE, null, account, loadout, policy));
    }

    private static PathfinderPlans.Want want(int item, int n) {
        return new PathfinderPlans.Want(item, null, n, n, n, false);
    }
}
