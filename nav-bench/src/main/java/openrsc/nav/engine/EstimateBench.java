package openrsc.nav.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Pathfinder;
import openrsc.nav.TravelTime;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.MappedNavCaches;
import openrsc.nav.cache.NavCacheBudget;
import openrsc.nav.cache.NavCaches;
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
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Times whole estimates through {@link Pathfinder#travelCost}: {@value #SOURCES} random standable
 * floor-0 tiles priced against every bank, keeping the pairs that reach, asked in shuffled order.
 * In the engine's package to reach its package-private types.
 *
 * <p>{@code probe} asks as the all-eligible probe; {@code fresh} as a level-1 account carrying and
 * banking nothing. {@code store=warm} reads what earlier passes built; {@code store=none} keeps
 * nothing. {@code store=mapped} keeps the caches in files under {@code scratch/} and measures an
 * engine reopened over them; its setup also prints one pass of a fresh heap engine against one pass
 * of the reopened one.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms8g", "-Xmx8g"})
public class EstimateBench {

    private static final int SOURCES = 256;
    /** Enough passes for every source cluster's leg plane to finish filling. */
    private static final int WARM_PASSES = 5;

    @Param({"probe", "fresh"})
    public String asker;

    @Param({"warm", "none", "mapped"})
    public String store;

    private ServerData env;
    private MappedNavCaches mapped;
    private Path mappedDir;
    private Pathfinder pathfinder;
    private AccountFacts account;
    /** Four ints per reachable pair: from x, y, then the bank's x, y. */
    private int[] pairs;

    private int[] order;
    private int cursor;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            env = ServerData.load(conf, world, landscape);
        }
        boolean keepsNothing = "none".equals(store);
        pathfinder = Pathfinder.load(env, openCaches());
        account = "fresh".equals(asker) ? fresh(env) : null;

        CollisionMap map = env.collisionMap();
        SplittableRandom r = new SplittableRandom(7);
        List<Point> banks = Banks.tiles(env);
        int[] all = new int[SOURCES * banks.size() * 4];
        int n = 0;
        for (int s = 0; s < SOURCES; ) {
            int x = r.nextInt(CollisionMap.WIDTH);
            int y = r.nextInt(CollisionMap.FLOOR_HEIGHT);
            if (map.fullyBlocked(x, y)) {
                continue;
            }
            s++;
            for (Point b : banks) {
                if (pathfinder.travelCost(x, y, b.x(), b.y(), account, null).isReachable()) {
                    all[n++] = x;
                    all[n++] = y;
                    all[n++] = b.x();
                    all[n++] = b.y();
                }
            }
        }
        pairs = Arrays.copyOf(all, n);
        int count = n / 4;
        order = new int[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        for (int i = count - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = order[i];
            order[i] = order[j];
            order[j] = t;
        }
        for (int pass = 1; pass < (keepsNothing ? 1 : WARM_PASSES); pass++) {
            pass(pathfinder);
        }
        if (mapped != null) {
            // The page cache is warm here: the warm-up has only just written the files.
            long heapMs = pass(Pathfinder.load(env, new CaffeineNavCaches(NavCacheBudget.DEFAULTS)));
            mapped.close();
            pathfinder = Pathfinder.load(env, openCaches());
            long reopenedMs = pass(pathfinder);
            System.out.println("EstimateBench: first pass " + heapMs + "ms on a fresh heap engine, " + reopenedMs
                    + "ms reopened over the files");
            for (int pass = 1; pass < WARM_PASSES; pass++) {
                pass(pathfinder);
            }
        }
        System.out.println("EstimateBench: " + count + " reachable pairs for " + asker + "/" + store);
    }

    @TearDown(Level.Trial)
    public void tearDown() throws Exception {
        if (mapped != null) {
            mapped.close();
            try (Stream<Path> files = Files.walk(mappedDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    /** Asks every pair once, in the shuffled order; returns the time taken, in ms. */
    private long pass(Pathfinder engine) {
        long t0 = System.nanoTime();
        for (int i = 0; i < order.length; i++) {
            int p = order[i] * 4;
            engine.travelCost(pairs[p], pairs[p + 1], pairs[p + 2], pairs[p + 3], account, null);
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    private NavCaches openCaches() throws IOException {
        return switch (store) {
            case "none" -> NavCaches.none();
            case "mapped" -> {
                if (mappedDir == null) {
                    Files.createDirectories(Path.of("scratch"));
                    mappedDir = Files.createTempDirectory(Path.of("scratch"), "estimate-");
                }
                mapped = new MappedNavCaches(mappedDir, NavCacheBudget.DEFAULTS, 1L << 32);
                yield mapped;
            }
            default -> new CaffeineNavCaches(NavCacheBudget.DEFAULTS);
        };
    }

    @Benchmark
    public TravelTime estimate() {
        int p = order[cursor++ % order.length] * 4;
        return pathfinder.travelCost(pairs[p], pairs[p + 1], pairs[p + 2], pairs[p + 3], account, null);
    }

    private static AccountFacts fresh(ServerData env) {
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
