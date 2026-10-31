package openrsc.nav.engine;

import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCacheBudget;
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
 * Times customizing one routing class's metric over the real map's contraction hierarchy: what the
 * first query of a class not seen before pays. In the engine's package to reach its package-private
 * types. Run with {@code java -jar target/benchmarks.jar CustomizeBench}.
 *
 * <p>{@code mask=all} makes every transport usable; {@code mask=random} rotates through 64 classes
 * that each keep a random 70% of them. {@code danger} charges rung 0's danger field at full weight,
 * or not at all.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms4g", "-Xmx4g"})
public class CustomizeBench {

    @Param({"all", "random"})
    public String mask;

    @Param({"on", "off"})
    public String danger;

    /**
     * {@code whole} customizes from nothing; {@code changed} updates the base metric (every ungated
     * transport usable, no gated one) by the class's gated transports; {@code child} updates the
     * class's own metric by 8 more gated transports.
     */
    @Param({"whole", "changed", "child"})
    public String how;

    private CchMetric base;
    private int[][] changes;

    private CchMetric[] parents;
    private boolean[][] children;

    private CchTopology topo;
    private DangerField field;
    private boolean[][] masks;
    private int[] costs;
    private int next;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        ServerData env;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            env = ServerData.load(conf, world, landscape);
        }
        Engine engine = (Engine) Pathfinder.load(env, new CaffeineNavCaches(NavCacheBudget.DEFAULTS))
                .nav();
        topo = engine.topology();
        field = new DangerField(engine.dangerMap(), 0, "on".equals(danger) ? 100 : 0);
        List<Transport> transports = engine.graph().transports();
        costs = new int[transports.size()];
        for (int i = 0; i < costs.length; i++) {
            costs[i] = Math.max(0, transports.get(i).cost());
        }
        SplittableRandom rnd = new SplittableRandom(7);
        masks = new boolean[64][costs.length];
        for (boolean[] m : masks) {
            for (int i = 0; i < m.length; i++) {
                m[i] = "all".equals(mask) || rnd.nextInt(10) < 7;
            }
        }
        if (!"whole".equals(how)) {
            for (boolean[] m : masks) {
                for (int i = 0; i < m.length; i++) {
                    m[i] |= !topo.gated[i];
                }
            }
            boolean[] ungated = new boolean[costs.length];
            for (int i = 0; i < ungated.length; i++) {
                ungated[i] = !topo.gated[i];
            }
            base = CchMetric.customize(topo, field, ungated, costs, false);
            changes = new int[64][];
            parents = new CchMetric[64];
            children = new boolean[64][];
            for (int k = 0; k < 64; k++) {
                int[] list = new int[costs.length];
                int n = 0;
                if ("changed".equals(how)) {
                    for (int i = 0; i < costs.length; i++) {
                        if (topo.gated[i] && masks[k][i]) {
                            list[n++] = i;
                        }
                    }
                } else if ("child".equals(how)) {
                    parents[k] = CchMetric.customize(topo, field, masks[k], costs, false);
                    children[k] = masks[k].clone();
                    while (n < 8) {
                        int i = rnd.nextInt(costs.length);
                        if (topo.gated[i] && !children[k][i]) {
                            children[k][i] = true;
                            list[n++] = i;
                        }
                    }
                }
                changes[k] = java.util.Arrays.copyOf(list, n);
            }
        }
        CchMetric.customize(topo, field, masks[0], costs, false); // builds the danger matrices outside the measurement
        System.out.println("CustomizeBench: " + topo.describe());
    }

    @Benchmark
    public Object customize() {
        int k = next++ & 63;
        boolean[] mask = masks[k];
        return switch (how) {
            case "changed" -> CchMetric.customizeChanged(base, field, mask, costs, changes[k]);
            case "child" -> CchMetric.customizeChanged(parents[k], field, children[k], costs, changes[k]);
            default -> CchMetric.customize(topo, field, mask, costs, false);
        };
    }
}
