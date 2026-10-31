package openrsc.nav.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.Planner;
import openrsc.nav.TravelTime;
import openrsc.nav.TravelTimes;
import openrsc.nav.cache.CaffeineNavCaches;
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
import org.openjdk.jmh.annotations.Warmup;

/**
 * Times whole planner calls through the public {@link Planner} API, so it measures any engine behind
 * it. Run with {@code java -jar target/benchmarks.jar PlannerBench}.
 *
 * <ul>
 *   <li>{@code fleet}: 12 accounts, each keeping one facts object, asking from 256 floor-0 tiles to
 *       every bank and 32 other sites: a small destination set asked again and again.
 *   <li>{@code saas}: a random account of 512 per query, with a new facts object each time, between
 *       random walkable tiles on any floor.
 *   <li>{@code unique}: as {@code saas}, but every call brings an account not seen before, so no
 *       routing class is asked twice.
 * </ul>
 *
 * <p>{@code store=warm} measures after three passes over the queries have filled the caches;
 * {@code store=none} caches nothing. Setup also prints the time of the first pass over a fresh
 * engine, the cold cost a workload pays on its way to warm.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 3)
@Measurement(iterations = 5, time = 3)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xmx5g"})
public class PlannerBench {

    private static final int QUERIES = 4096;
    private static final int BATCH = 16;

    @Param({"fleet", "saas", "unique"})
    public String workload;

    @Param({"warm", "none"})
    public String store;

    /** The metric cache's budget in MB. */
    @Param({"256"})
    public int metricMb;

    private ServerData env;
    private Pathfinder pathfinder;
    private Planner nav;
    private AccountFacts[] accounts;
    /** Five ints per query: the account index, then from and to as x, y. */
    private int[] queries;

    private AccountFacts[] fleetFacts;
    private List<Dest> siteDests = List.of();

    private int cursor;
    private int uniqueNext;

    @Setup(Level.Trial)
    public void setUp() throws Exception {
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            env = ServerData.load(conf, world, landscape);
        }
        NavCacheBudget d = NavCacheBudget.DEFAULTS;
        pathfinder = Pathfinder.load(
                env,
                "none".equals(store)
                        ? NavCaches.none()
                        : new CaffeineNavCaches(
                                new NavCacheBudget((long) metricMb << 20, d.goalLabelBytes(), d.legBytes())));
        nav = pathfinder.nav();
        SplittableRandom rnd = new SplittableRandom(20260914L);
        CollisionMap map = env.collisionMap();
        int[] gateItems = pathfinder.eligibilityItemIds();
        int[] gateQuests = pathfinder.eligibilityQuestIds();
        boolean saas = !"fleet".equals(workload);
        accounts = new AccountFacts["unique".equals(workload) ? 1 << 16 : saas ? 512 : 12];
        for (int i = 0; i < accounts.length; i++) {
            accounts[i] = randomAccount(rnd, map, gateItems, gateQuests);
        }
        fleetFacts = new AccountFacts[accounts.length];
        for (int i = 0; i < accounts.length; i++) {
            fleetFacts[i] =
                    accounts[i].at(accounts[i].pos().x(), accounts[i].pos().y());
        }
        queries = new int[QUERIES * 5];
        List<Point> sites = new ArrayList<>(Banks.tiles(env));
        for (int i = 0; i < 32; i++) {
            sites.add(tile(rnd, map, false));
        }
        siteDests = new ArrayList<>(sites);
        List<Point> sources = new ArrayList<>();
        for (int i = 0; i < 256; i++) {
            sources.add(tile(rnd, map, false));
        }
        for (int q = 0; q < QUERIES; q++) {
            Point from = saas ? tile(rnd, map, true) : sources.get(rnd.nextInt(sources.size()));
            Point to = saas ? tile(rnd, map, true) : sites.get(rnd.nextInt(sites.size()));
            queries[5 * q] = rnd.nextInt(accounts.length);
            queries[5 * q + 1] = from.x();
            queries[5 * q + 2] = from.y();
            queries[5 * q + 3] = to.x();
            queries[5 * q + 4] = to.y();
        }
        long t0 = System.nanoTime();
        for (int q = 0; q < QUERIES; q++) {
            estimateAt(q);
        }
        System.out.printf(
                "PlannerBench %s/%s: first pass %.1fus per estimate%n",
                workload, store, (System.nanoTime() - t0) / 1e3 / QUERIES);
        for (int pass = 0; pass < ("warm".equals(store) ? 2 : 0); pass++) {
            for (int q = 0; q < QUERIES; q++) {
                estimateAt(q);
            }
        }
        System.out.printf("PlannerBench %s/%s: %s%n", workload, store, nav.cacheStats());
    }

    private AccountFacts factsFor(int q) {
        int a = "unique".equals(workload) ? uniqueNext++ & (accounts.length - 1) : queries[5 * q];
        return "fleet".equals(workload) ? fleetFacts[a] : accounts[a].at(queries[5 * q + 1], queries[5 * q + 2]);
    }

    private TravelTime estimateAt(int q) {
        Point from = Point.of(queries[5 * q + 1], queries[5 * q + 2]);
        return nav.estimate(factsFor(q), from, Point.of(queries[5 * q + 3], queries[5 * q + 4]), NavPolicy.DEFAULT);
    }

    @Benchmark
    public TravelTime estimate() {
        return estimateAt(cursor++ % QUERIES);
    }

    @Benchmark
    public Object plan() {
        int q = cursor++ % QUERIES;
        Point from = Point.of(queries[5 * q + 1], queries[5 * q + 2]);
        return nav.plan(new PlanRequest(
                factsFor(q),
                from,
                Point.of(queries[5 * q + 3], queries[5 * q + 4]),
                LoadoutSpec.any(),
                NavPolicy.DEFAULT));
    }

    /**
     * Times classifying the account on its own: {@code dangerAt} resolves the account and reads one
     * tile. A new facts object is resolved anew each call, so under {@code saas} this is a floor
     * under every query.
     */
    @Benchmark
    public int resolve() {
        int q = cursor++ % QUERIES;
        return nav.dangerAt(factsFor(q), NavPolicy.DEFAULT, queries[5 * q + 3], queries[5 * q + 4]);
    }

    @Benchmark
    public TravelTimes batch() {
        int q = cursor;
        cursor += BATCH;
        List<Dest> dests = new ArrayList<>(BATCH);
        for (int i = 0; i < BATCH; i++) {
            int k = (q + i) % QUERIES;
            dests.add(Point.of(queries[5 * k + 3], queries[5 * k + 4]));
        }
        Point from = Point.of(queries[5 * (q % QUERIES) + 1], queries[5 * (q % QUERIES) + 2]);
        return nav.estimateBatch(factsFor(q % QUERIES), from, dests, NavPolicy.DEFAULT);
    }

    /** One account priced, from where it stands, against every site in one batch. */
    @Benchmark
    public TravelTimes sweep() {
        int q = cursor++ % QUERIES;
        Point from = Point.of(queries[5 * q + 1], queries[5 * q + 2]);
        return nav.estimateBatch(factsFor(q), from, siteDests, NavPolicy.DEFAULT);
    }

    private static Point tile(SplittableRandom rnd, CollisionMap map, boolean anyFloor) {
        while (true) {
            int x = rnd.nextInt(CollisionMap.WIDTH);
            int floors = anyFloor && rnd.nextInt(10) >= 7 ? CollisionMap.FLOOR_COUNT : 1;
            int y = rnd.nextInt(CollisionMap.FLOOR_HEIGHT * floors);
            if (map.inBounds(x, y) && !map.fullyBlocked(x, y)) {
                return Point.of(x, y);
            }
        }
    }

    /** Random levels, quest stages, and carried and banked gate items: a routing class apiece. */
    private AccountFacts randomAccount(SplittableRandom rnd, CollisionMap map, int[] gateItems, int[] gateQuests) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            int level = 1 + rnd.nextInt(99);
            stats.setSkill(sk.ordinal(), level, level, 0);
        }
        MutableQuests quests = new MutableQuests();
        for (int q : gateQuests) {
            switch (rnd.nextInt(4)) {
                case 1 -> quests.setStage(q, 1 + rnd.nextInt(6));
                case 2 -> quests.setStage(q, -1);
                default -> {}
            }
        }
        MutableInventory inv = new MutableInventory();
        int slot = 0;
        inv.setSlot(slot, new InventorySlot(slot, ItemId.COINS.id(), rnd.nextInt(120), false));
        slot++;
        for (int id : gateItems) {
            if (slot < 28 && rnd.nextInt(10) == 0) {
                int amount = env.isStackable(id) ? 1 + rnd.nextInt(60) : 1;
                inv.setSlot(slot, new InventorySlot(slot, id, amount, rnd.nextInt(3) == 0));
                slot++;
            }
        }
        MutableBank bank = new MutableBank();
        int b = 0;
        bank.setSlot(b, new BankSlot(b, ItemId.COINS, rnd.nextInt(2000)));
        b++;
        for (int id : gateItems) {
            ItemId known = ItemId.getById(id);
            if (known != null && rnd.nextInt(4) == 0) {
                bank.setSlot(b, new BankSlot(b, known, env.isStackable(id) ? 1 + rnd.nextInt(300) : 1));
                b++;
            }
        }
        return new AccountFacts.Snapshot(
                env, inv.snapshot(), bank.snapshot(), stats.snapshot(), quests.snapshot(), tile(rnd, map, false));
    }
}
