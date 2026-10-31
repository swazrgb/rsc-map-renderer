package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
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
import openrsc.nav.cache.MappedNavCaches;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MappedPlannerParityTest {

    private static final int[][] PAIRS = {
        {86, 104, 651, 848},
        {122, 647, 310, 545},
        {651, 848, 120, 504},
        {126, 670, 220, 640},
        {122, 647, 126, 670},
    };

    private static final int PLANE_BUILDS = 1; // index into workCounters()

    @TempDir
    Path dir;

    private static ServerData env;
    private static Pathfinder heap;
    private static AccountFacts fresh;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        heap = RealDefs.cachedPathfinder();
        fresh = fresh();
    }

    @Test
    @DisplayName("a file-backed engine answers like the heap one, and its next run builds less")
    void answersMatchAndARestartReadsInstead() throws Exception {
        long[] firstRun;
        List<String> mismatches = new ArrayList<>();
        try (MappedNavCaches caches = new MappedNavCaches(dir, RealDefs.CACHE_BUDGET, 1L << 30)) {
            Pathfinder mapped = Pathfinder.load(env, caches);
            mapped.warmUp(List.of(fresh));
            compare("first run", mapped, mismatches);
            firstRun = mapped.nav().workCounters();
        }
        assertTrue(Files.size(dir.resolve("cluster_legs").resolve("index.log")) > 0, "leg planes were written");

        long[] secondRun;
        try (MappedNavCaches caches = new MappedNavCaches(dir, RealDefs.CACHE_BUDGET, 1L << 30)) {
            Pathfinder reopened = Pathfinder.load(env, caches);
            reopened.warmUp(List.of(fresh));
            compare("after a restart", reopened, mismatches);
            secondRun = reopened.nav().workCounters();
        }

        assertTrue(mismatches.isEmpty(), String.join("\n", mismatches));
        assertTrue(
                secondRun[PLANE_BUILDS] < firstRun[PLANE_BUILDS],
                "leg planes came off disk: plane builds " + firstRun[PLANE_BUILDS] + " then "
                        + secondRun[PLANE_BUILDS]);
    }

    private static void compare(String when, Pathfinder mapped, List<String> mismatches) {
        for (AccountFacts account : Arrays.asList(null, fresh)) {
            for (int[] p : PAIRS) {
                for (int ask = 0; ask < 6; ask++) {
                    TravelTime want = heap.travelCost(p[0], p[1], p[2], p[3], account, null);
                    TravelTime got = mapped.travelCost(p[0], p[1], p[2], p[3], account, null);
                    if (!want.equals(got)) {
                        mismatches.add(String.format(
                                "%s, ask %d, (%d,%d)->(%d,%d) as %s: heap=%s mapped=%s",
                                when, ask, p[0], p[1], p[2], p[3], account == null ? "probe" : "fresh", want, got));
                    }
                }
            }
        }
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
