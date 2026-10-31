package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCaches;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UncachedPlannerParityTest {

    private static final int ASKS = 8; // every ask after the first reads what the first one cached

    private static final int[][] PAIRS = {
        {86, 104, 651, 848},
        {122, 647, 310, 545},
        {575, 651, 67, 744},
        {651, 848, 120, 504},
        {126, 670, 220, 640},
        {122, 647, 126, 670},
    };

    // A level-1 account cannot reach (575, 651), and a pair with no route fails the comparison.
    private static final int[][] FRESH_PAIRS =
            Arrays.stream(PAIRS).filter(p -> !(p[0] == 575 && p[1] == 651)).toArray(int[][]::new);

    private static final int METRIC_BUILDS = 0; // indices into workCounters()

    private static final int SEARCHES = 3;

    private static ServerData env;
    private static Pathfinder cached;
    private static Pathfinder uncached;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        cached = Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET));
        uncached = Pathfinder.load(env, NavCaches.none());
    }

    @AfterAll
    static void release() {
        cached = null;
        uncached = null;
    }

    @Test
    @DisplayName("a fresh account's routes cost the same uncached as through corridor and promotion")
    void freshAccountMatches() {
        long[] cachedBefore = cached.nav().workCounters();
        long[] uncachedBefore = uncached.nav().workCounters();

        List<String> mismatches = compareAll(fresh(), FRESH_PAIRS);

        long[] cachedAfter = cached.nav().workCounters();
        long[] uncachedAfter = uncached.nav().workCounters();
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
        int asked = FRESH_PAIRS.length * ASKS;
        assertTrue(
                cachedAfter[SEARCHES] - cachedBefore[SEARCHES] >= asked,
                "the cached engine answered without searching the hierarchy");
        assertTrue(
                cachedAfter[METRIC_BUILDS] - cachedBefore[METRIC_BUILDS] <= 1,
                "the cached engine customized its one class more than once: "
                        + (cachedAfter[METRIC_BUILDS] - cachedBefore[METRIC_BUILDS]));
        assertTrue(
                uncachedAfter[METRIC_BUILDS] - uncachedBefore[METRIC_BUILDS] >= FRESH_PAIRS.length - 1,
                "the uncached engine kept a metric it has nowhere to keep");
    }

    @Test
    @DisplayName("the all-eligible probe's routes cost the same uncached as cached")
    void allEligibleProbeMatches() {
        List<String> mismatches = compareAll(null, PAIRS);
        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
    }

    private static List<String> compareAll(AccountFacts account, int[][] pairs) {
        List<String> mismatches = new ArrayList<>();
        for (int[] p : pairs) {
            TravelTime expected = uncached.travelCost(p[0], p[1], p[2], p[3], account, null);
            TravelTime[] asks = new TravelTime[ASKS];
            boolean agrees = expected.isReachable();
            for (int i = 0; i < ASKS; i++) {
                asks[i] = cached.travelCost(p[0], p[1], p[2], p[3], account, null);
                agrees &= asks[i].equals(expected);
            }
            if (!agrees) {
                mismatches.add(String.format(
                        "(%d,%d)->(%d,%d) as %s: uncached=%s, cached asks=%s",
                        p[0], p[1], p[2], p[3], account == null ? "probe" : "fresh", expected, Arrays.toString(asks)));
            }
        }
        return mismatches;
    }

    private static AccountFacts fresh() {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), 1, 1, 0);
        }
        return new AccountFacts.Snapshot(
                env,
                new MutableInventory().snapshot(),
                Bank.empty(),
                stats.snapshot(),
                new MutableQuests().snapshot(),
                Point.of(126, 670));
    }
}
