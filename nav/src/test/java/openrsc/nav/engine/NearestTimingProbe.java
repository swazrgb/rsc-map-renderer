package openrsc.nav.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.world.ChunkBox;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Planner.StaticTargets;
import openrsc.nav.RealDefs;
import openrsc.nav.cache.CaffeineNavCaches;
import org.junit.jupiter.api.Test;

class NearestTimingProbe {
    @Test
    void probe() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET));
        Engine e = (Engine) pf.nav();
        CollisionMap map = env.collisionMap();
        SplittableRandom rnd = new SplittableRandom(3);
        int grid = env.world().liveViewGridRadius();
        for (int[] ids : List.of(new int[] {0, 1}, new int[] {70}, new int[] {34}, new int[] {113})) {
            int n = 0;
            for (int id : ids) {
                n += map.staticLocIndex().sceneryWithId(id).length;
            }
            List<Point> tiles = new ArrayList<>();
            List<AccountFacts> accounts = new ArrayList<>();
            while (tiles.size() < 10) {
                int x = 100 + rnd.nextInt(500), y = 450 + rnd.nextInt(400);
                if (map.fullyBlocked(x, y) || map.fullyBlocked(x + 1, y) || map.fullyBlocked(x + 9, y)) {
                    continue;
                }
                tiles.add(Point.of(x, y));
                accounts.add(PrereqProgramTest.account(env, rnd, pf.eligibilityItemIds(), pf.eligibilityQuestIds())
                        .at(x, y));
            }
            for (int i = 0; i < tiles.size(); i++) {
                Point p = tiles.get(i);
                for (int dx : new int[] {0, 1, 9}) {
                    Point q = Point.of(p.x() + dx, p.y());
                    e.nearest(accounts.get(i), q, List.of(), st(ids, q, grid), NavPolicy.DEFAULT);
                }
            }
            java.lang.management.ThreadMXBean cpu = java.lang.management.ManagementFactory.getThreadMXBean();
            long[] newChunkT = new long[tiles.size()], repeatT = new long[tiles.size()], stepT = new long[tiles.size()];
            long labels = 0;
            for (int i = 0; i < tiles.size(); i++) {
                Point p = tiles.get(i);
                AccountFacts a = accounts.get(i);
                Point far = Point.of(p.x() + 17, p.y());
                if (!map.fullyBlocked(far.x(), far.y())) {
                    long b0 = e.targetLabelBuilds();
                    long t0 = cpu.getCurrentThreadCpuTime();
                    e.nearest(a, far, List.of(), st(ids, far, grid), NavPolicy.DEFAULT);
                    newChunkT[i] = cpu.getCurrentThreadCpuTime() - t0;
                    labels += e.targetLabelBuilds() - b0;
                }
                e.nearest(a, p, List.of(), st(ids, p, grid), NavPolicy.DEFAULT);
                long t1 = cpu.getCurrentThreadCpuTime();
                for (int k = 0; k < 200; k++) {
                    e.nearest(a, p, List.of(), st(ids, p, grid), NavPolicy.DEFAULT);
                }
                repeatT[i] = (cpu.getCurrentThreadCpuTime() - t1) / 200;
                Point q = Point.of(p.x() + 1, p.y());
                long t2 = cpu.getCurrentThreadCpuTime();
                e.nearest(a, q, List.of(), st(ids, q, grid), NavPolicy.DEFAULT);
                stepT[i] = cpu.getCurrentThreadCpuTime() - t2;
                e.nearest(a, p, List.of(), st(ids, p, grid), NavPolicy.DEFAULT);
            }
            System.out.printf(
                    "ids %s (%d locs), CPU medians: new box %.2f ms (%d label builds of %d), repeat %.1f us, one step %.1f us%n",
                    java.util.Arrays.toString(ids),
                    n,
                    median(newChunkT) / 1e6,
                    labels,
                    tiles.size(),
                    median(repeatT) / 1e3,
                    median(stepT) / 1e3);
        }
    }

    private static double median(long[] v) {
        long[] c = v.clone();
        java.util.Arrays.sort(c);
        return c[c.length / 2];
    }

    private static StaticTargets st(int[] ids, Point at, int grid) {
        return new StaticTargets(Targetable.Kind.SCENERY, ids, ChunkBox.around(at.x(), at.y(), grid), new long[0]);
    }
}
