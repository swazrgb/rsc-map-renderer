package openrsc.nav;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.LoadProgress;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.cache.NavCaches;
import openrsc.nav.eligibility.EligibilityInputs;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.eligibility.SkillBreakpoints;
import openrsc.nav.probe.NavProbe;
import openrsc.nav.transports.AllTransports;
import openrsc.nav.transports.Banks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entry point for planning on one world: the transport registry for it, the {@link Planner}
 * built over that registry, and the {@link HostedWalkerKit} a route follower takes.
 *
 * <pre>{@code
 * Pathfinder pathfinder = Pathfinder.load(serverData, NavCaches.none());
 * Path path = pathfinder.findPath(127, 127, 608, 841);
 * }</pre>
 *
 * <p>Build one per {@link ServerData} with {@link #load} and share it: it is immutable after
 * loading and safe to use from any number of threads. There is no global registry; whoever loads
 * it holds it, and its planner's caches live as long as it does.
 *
 * <p>The tile probes ({@link #findPath}, {@link #probe}, {@link #travelCost}) take a null account
 * for a level-99 account holding every item a gate asks for, with every gate open
 * ({@link NavPolicy#withAllEligible()}), and a null policy for {@link NavPolicy#DEFAULT}.
 */
public final class Pathfinder {

    private static final Logger LOG = LoggerFactory.getLogger(Pathfinder.class);

    private static final int[] WARM_ROUTE = {127, 127, 608, 841};

    private final ServerData env;
    private final List<Transport> transports;
    private final NavInputs inputs;
    private final Planner nav;
    private final HostedWalkerKit walkerKit;
    private volatile boolean warmed;

    private Pathfinder(ServerData env, List<Transport> transports, NavInputs inputs, Planner nav) {
        this.env = env;
        this.transports = transports;
        this.inputs = inputs;
        this.nav = nav;
        this.walkerKit = new HostedWalkerKit(nav, transports, env);
    }

    /**
     * Derives the transport registry from {@code env}'s landscape and definitions and builds the
     * planner over it. Slow: build once and pass it around.
     */
    public static Pathfinder load(ServerData env, NavCaches caches) {
        Objects.requireNonNull(env, "env");
        Objects.requireNonNull(caches, "caches");
        Instant t0 = Instant.now();
        LoadProgress.stage("deriving transports from the world");
        List<Transport> transports = AllTransports.defaultRegistry(env);
        NavInputs inputs = new NavInputs(env, transports, Banks.on(env), caches);
        Planner nav = Navigation.load(inputs);
        LOG.info(
                "Pathfinder: engine loaded over {} transports in {}ms",
                transports.size(),
                Duration.between(t0, Instant.now()).toMillis());
        return new Pathfinder(env, transports, inputs, nav);
    }

    public ServerData serverData() {
        return env;
    }

    /** The transports the planner plans with, in registry order. */
    public List<Transport> transports() {
        return transports;
    }

    public NavInputs navInputs() {
        return inputs;
    }

    public Planner nav() {
        return nav;
    }

    public HostedWalkerKit walkerKit() {
        return walkerKit;
    }

    /**
     * Plans one long trip across the map so the first real plan does not pay for loading and
     * compiling the planner's code paths. Only the first call does anything; a failure is logged,
     * not thrown.
     */
    public void warmUp() {
        if (warmed) {
            return;
        }
        synchronized (this) {
            if (warmed) {
                return;
            }
            try {
                findPath(WARM_ROUTE[0], WARM_ROUTE[1], WARM_ROUTE[2], WARM_ROUTE[3]);
            } catch (RuntimeException e) {
                LOG.warn("Pathfinder warm-up failed (non-fatal)", e);
            }
            warmed = true;
        }
    }

    /** {@link #warmUp()}, then {@link Planner#warmClasses} for {@code fleet}; a failure is logged, not thrown. */
    public void warmUp(Collection<? extends AccountFacts> fleet) {
        warmUp();
        if (fleet == null || fleet.isEmpty()) {
            return;
        }
        try {
            Instant t0 = Instant.now();
            int classes = nav.warmClasses(fleet, NavPolicy.DEFAULT);
            LOG.info(
                    "nav warm-up: {} accounts, {} routing classes in {} ms",
                    fleet.size(),
                    classes,
                    Duration.between(t0, Instant.now()).toMillis());
        } catch (RuntimeException e) {
            LOG.warn("Pathfinder fleet warm-up failed (non-fatal)", e);
        }
    }

    /** {@link #findPath(int, int, int, int, AccountFacts, NavPolicy)} for the all-eligible account. */
    public Path findPath(int sx, int sy, int gx, int gy) {
        return findPath(sx, sy, gx, gy, null, null);
    }

    /**
     * Walks a simulated account from {@code (sx, sy)} to {@code (gx, gy)} one tick at a time, as the
     * route follower would, for at most 6,000 ticks. Null when the first plan finds no route; a walk
     * that gets stuck later, or runs out of ticks, returns the part it walked.
     */
    public Path findPath(int sx, int sy, int gx, int gy, AccountFacts account, NavPolicy policy) {
        return pathFromProbe(NavProbe.planBetween(this, sx, sy, gx, gy, account, policy));
    }

    /**
     * The walk {@link #findPath(int, int, int, int, AccountFacts, NavPolicy)} makes, with each hop
     * and bank visit described.
     */
    public List<NavProbe.ProbeStep> probe(int sx, int sy, int gx, int gy, AccountFacts account, NavPolicy policy) {
        return NavProbe.planBetween(this, sx, sy, gx, gy, account, policy);
    }

    /** {@link Planner#estimate} between two tiles. */
    public TravelTime travelCost(int sx, int sy, int gx, int gy, AccountFacts account, NavPolicy policy) {
        return NavProbe.estimateBetween(this, sx, sy, gx, gy, account, policy);
    }

    /** {@link Planner#estimateBatch} from one tile to each of {@code goals}, each {@code {x, y}}. */
    public TravelTimes travelCostBatch(int sx, int sy, int[][] goals, AccountFacts account, NavPolicy policy) {
        return NavProbe.estimateBatchBetween(this, sx, sy, goals, account, policy);
    }

    /**
     * How many routing-class metrics and walking planes the planner has built so far: its
     * millisecond-scale work, each done once and then cached.
     */
    public long expensiveOps() {
        long[] w = nav.workCounters();
        return w[0] + w[1];
    }

    /** The item ids any transport's gate reads, ascending. */
    public int[] eligibilityItemIds() {
        TreeSet<Integer> ids = new TreeSet<>();
        for (Transport spec : inputs.transports()) {
            for (var f : spec.prereq().facts()) {
                switch (f.kind()) {
                    case CARRIED_AT_LEAST, WEARING, NOT_CARRYING -> ids.add(f.itemId());
                    default -> {}
                }
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The quest ids any transport's gate reads, ascending. */
    public int[] eligibilityQuestIds() {
        TreeSet<Integer> ids = new TreeSet<>();
        for (Transport spec : inputs.transports()) {
            for (var f : spec.prereq().facts()) {
                switch (f.kind()) {
                    case QUEST_AT_LEAST, QUEST_COMPLETE, QUEST_EQUALS -> ids.add(f.itemId());
                    default -> {}
                }
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Per skill, the levels at which some transport's gate changes its answer. */
    public SkillBreakpoints skillBreakpoints() {
        LinkedHashSet<EligibilityInputs.Fact> facts = new LinkedHashSet<>();
        for (Transport t : inputs.transports()) {
            facts.addAll(Arrays.asList(t.prereq().facts()));
        }
        return SkillBreakpoints.of(facts);
    }

    /** Every named family of items some gate asks for ("a pickaxe"), the most-asked first. */
    public List<ItemGroup> itemGroups() {
        Map<String, int[]> members = new LinkedHashMap<>();
        Map<String, Integer> asked = new LinkedHashMap<>();
        for (Transport t : inputs.transports()) {
            for (Prereq part : t.prereq().namedParts()) {
                members.putIfAbsent(part.description(), part.inputs().itemIds());
                asked.merge(part.description(), 1, Integer::sum);
            }
        }
        List<ItemGroup> out = new ArrayList<>(members.size());
        members.forEach((label, ids) -> out.add(new ItemGroup(label, ids, asked.get(label))));
        out.sort(Comparator.comparingInt(ItemGroup::transports).reversed());
        return out;
    }

    /**
     * A named family of items a gate accepts any one of.
     *
     * @param transports how many transports ask for it
     */
    public record ItemGroup(String label, int[] itemIds, int transports) {}

    private Path pathFromProbe(List<NavProbe.ProbeStep> probe) {
        if (probe == null || probe.isEmpty()) {
            return null;
        }
        Map<String, Transport> byName = new HashMap<>();
        for (Transport t : transports) {
            byName.putIfAbsent(t.name(), t);
        }
        int w = CollisionMap.WIDTH;
        int[] tiles = new int[probe.size()];
        List<Integer> hopIdx = new ArrayList<>();
        List<Transport> hops = new ArrayList<>();
        for (int i = 0; i < probe.size(); i++) {
            NavProbe.ProbeStep ps = probe.get(i);
            tiles[i] = ps.y() * w + ps.x();
            if (ps.hopName() != null) {
                hopIdx.add(i);
                hops.add(byName.get(ps.hopName()));
            }
        }
        int[] hopAt = new int[hopIdx.size()];
        for (int i = 0; i < hopAt.length; i++) {
            hopAt[i] = hopIdx.get(i);
        }
        Path path = new Path(w, tiles, hopAt, hops.toArray(new Transport[0]));
        for (int i = 0; i < probe.size(); i++) {
            if (probe.get(i).bankVisit()) {
                path.markBankVisit(i);
            }
        }
        return path;
    }
}
