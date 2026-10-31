package openrsc.nav.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCacheBudget;
import openrsc.nav.cache.NavCaches;
import openrsc.nav.transports.AllTransports;
import openrsc.nav.transports.Banks;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Times {@code Legs} over the real map: reads off finished planes ({@code costsAt}, and
 * {@code minOver4} for a 2 by 2 goal) and whole-plane builds ({@code buildPlane}, what a cache miss
 * pays). Asks are uniform over populated clusters and their tiles. In the engine's package to reach
 * its package-private types.
 *
 * <p>With {@code danger} on, the field is rung 0's at full weight, so the clusters it charges carry
 * a time channel and the rest do not.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms4g", "-Xmx4g"})
public class LegsBench {

    private static final int ASKS = 1 << 16;
    private static final int MASK = ASKS - 1;
    private static final int CS = ClusterGeometry.CLUSTER_SIZE;

    @Param({"false", "true"})
    public boolean danger;

    /** Holds every populated cluster's plane, built in setup. */
    private Legs warm;
    /** Caches nothing, so every ask builds the plane. */
    private Legs cold;

    private DangerField field;
    private int[] populated;
    private final int[] clusters = new int[ASKS];
    private final int[] xs = new int[ASKS];
    private final int[] ys = new int[ASKS];
    private final int[][] xs4 = new int[ASKS][4];
    private final int[][] ys4 = new int[ASKS][4];
    private int[] outTimes;
    private int cursor;
    private int buildCursor;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        ServerData env;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            env = ServerData.load(conf, world, landscape);
        }
        CollisionMap map = env.collisionMap();
        ClusterGraph graph = ClusterGraph.build(map, AllTransports.defaultRegistry(env), Banks.on(env));
        field = danger ? new DangerField(DangerMap.build(env.npcSpawns(), env::npc, map), 0, 100) : null;

        List<Integer> pop = new ArrayList<>();
        int maxNodes = 0;
        for (int c = 0; c < ClusterGeometry.TOTAL_CLUSTERS; c++) {
            int n = graph.clusterNodeCount(c);
            if (n > 0) {
                pop.add(c);
                maxNodes = Math.max(maxNodes, n);
            }
        }
        populated = pop.stream().mapToInt(Integer::intValue).toArray();
        outTimes = new int[maxNodes];

        warm = new Legs(map, graph, new CaffeineNavCaches(NavCacheBudget.DEFAULTS), () -> new byte[0]);
        cold = new Legs(map, graph, NavCaches.none(), () -> new byte[0]);
        for (int c : populated) {
            warm.toNodes(c, field);
        }

        SplittableRandom r = new SplittableRandom(7);
        for (int k = 0; k < ASKS; k++) {
            int c = populated[r.nextInt(populated.length)];
            int lx = r.nextInt(CS - 1);
            int ly = r.nextInt(CS - 1);
            clusters[k] = c;
            xs[k] = ClusterGeometry.minX(c) + lx;
            ys[k] = ClusterGeometry.minY(c) + ly;
            for (int t = 0; t < 4; t++) {
                xs4[k][t] = xs[k] + (t & 1);
                ys4[k][t] = ys[k] + (t >> 1);
            }
        }
    }

    @Benchmark
    public int costsAt() {
        int k = cursor++ & MASK;
        int[] costs = warm.costsAt(clusters[k], xs[k], ys[k], true, field, outTimes);
        return costs[0] ^ outTimes[0];
    }

    @Benchmark
    public int minOver4() {
        int k = cursor++ & MASK;
        int[] costs = warm.costsAtAny(clusters[k], xs4[k], ys4[k], 4, true, field, outTimes);
        return costs[0] ^ outTimes[0];
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.MICROSECONDS)
    public int buildPlane() {
        int c = populated[buildCursor++ % populated.length];
        return cold.toNodes(c, field).nodeCount;
    }
}
