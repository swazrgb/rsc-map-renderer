package openrsc.nav.engine;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.NoteForm;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.LoadProgress;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.ItemDef;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.BankSite;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.Manifest;
import openrsc.nav.NavInputs;
import openrsc.nav.NavPolicy;
import openrsc.nav.Packs;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.Route;
import openrsc.nav.Settle;
import openrsc.nav.TracedRoute;
import openrsc.nav.Transport;
import openrsc.nav.TravelTime;
import openrsc.nav.TravelTimes;
import openrsc.nav.cache.NavCache;
import openrsc.nav.cache.NavCacheStats;
import openrsc.nav.cache.NavStore;
import openrsc.nav.eligibility.EligibilityInputs;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Meals;
import openrsc.nav.eligibility.MeleeWeapons;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.eligibility.RouteContext;
import openrsc.nav.eligibility.SkillBreakpoints;
import openrsc.nav.walk.WindowSearch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The planner over a {@link NavInputs}: routes, estimates and walks priced over a customizable
 * contraction hierarchy of the static map ({@link ClusterGraph}, {@link CchTopology}).
 *
 * <p>Every answer is a function of the inputs' static data, the request's {@link AccountFacts} and
 * its {@link NavPolicy}. No overlay or live state is read, so nothing kept ever goes stale: a cache
 * needs only a key that holds everything its answer reads. Durations are deci-ticks
 * ({@link TravelTime}). Choices are made by weight, which is time plus the danger charge and the
 * teleport surcharge ({@link #TELEPORT_WEIGHT}), and on equal weight by time; what is reported is
 * always the chosen route's time.
 *
 * <p>Thread-safe and never synchronized on a query: every search runs on scratch of its thread,
 * and the stores are concurrent.
 */
public final class Engine implements Planner {

    /** Adds {@code stop} unless it moves nothing: a stop that moves nothing is no step of the trip. */
    private static void addStop(List<Route.Step> steps, Route.BankStop stop) {
        if (!stop.manifest().isEmpty()) {
            steps.add(stop);
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger(Engine.class);

    private final ServerData serverData;
    private final DangerMap dangerMap;
    private final CollisionMap map;
    private final ClusterGraph graph;
    private final Legs legs;
    private final List<Transport> transports;
    private final int[] baseCosts;
    private final int baseCostsHash;
    private final boolean[] allUsable;

    private final EligKey allUsableElig;
    private final String[] names;
    private final String[][][] policyKeys;
    private final Prereq[] uniquePrereqs;
    private final Fact[] distinctFacts;
    private final int[] bankRelaxedFacts;
    private final int[] packFacts;
    private final int[] roomFacts;
    private final SkillBreakpoints skillBreakpoints;
    private final int[][] prereqPrograms;
    private final int longestProgram;

    private final int[] prereqOf;
    private final int[] bankNodes;
    private final int[] bankCosts;
    private final Map<String, Integer> bankByName;
    private final Set<String> bankNames;

    private final ConcurrentHashMap<Integer, DangerField> dangerFields = new ConcurrentHashMap<>();
    private final CchTopology topology;
    private final NavCache<MetricKey, CchMetric> metrics;
    private final NavCache<LabelKey, Kept> goalLabels;
    private final StaticTargetTables staticTargets;
    private final ThreadLocal<int[]> ownerTl;
    private final ThreadLocal<CchSearch> searchTl;
    private final ThreadLocal<CchSearch> batchTl;
    private final ThreadLocal<CchSearch> bankedTl;
    private final AtomicReferenceArray<BatchRow> batchRows;

    private static final int BATCH_ROWS = 1 << 11;
    private static final int BATCH_ROW_MAX_DESTS = 256;
    private final AtomicLong metricBuilds = new AtomicLong();
    private final AtomicLong searches = new AtomicLong();
    private final String graphShape;

    /**
     * Builds the graph, the danger map and the hierarchy's topology: seconds on the full map, reported
     * through {@link LoadProgress}.
     *
     * @throws IllegalStateException when two banks share a name, or a bank shares one with a declared
     *     transport: a cost override names either
     */
    public Engine(NavInputs in) {
        this.serverData = in.serverData();
        LoadProgress.stage("mapping aggressive npcs");
        this.dangerMap = DangerMap.build(serverData.npcSpawns(), serverData::npc, serverData.collisionMap());
        this.map = serverData.collisionMap();
        this.transports = in.transports();
        LoadProgress.stage("clustering the walkable map");
        this.graph = ClusterGraph.build(map, transports, in.banks());

        this.baseCosts = new int[transports.size()];
        this.names = new String[transports.size()];
        this.policyKeys = new String[transports.size()][][];
        LoadProgress.stage("compiling transport gates");
        // Transports share gates heavily (door pairs, quest families): the gate trees are deduplicated, so
        // one pass over the unique set classifies an account.
        LinkedHashMap<Prereq, Integer> uniq = new LinkedHashMap<>();
        this.prereqOf = new int[transports.size()];
        for (int i = 0; i < transports.size(); i++) {
            Transport t = transports.get(i);
            prereqOf[i] = uniq.computeIfAbsent(t.prereq(), k -> uniq.size());
            baseCosts[i] = Math.max(0, t.cost());
            names[i] = t.name();
            List<List<String>> tiers = t.policyKeys();
            policyKeys[i] = new String[tiers.size()][];
            for (int k = 0; k < tiers.size(); k++) {
                policyKeys[i][k] = tiers.get(k).toArray(new String[0]);
            }
        }
        this.baseCostsHash = Arrays.hashCode(baseCosts);
        this.allUsable = new boolean[baseCosts.length];
        Arrays.fill(allUsable, true);
        this.allUsableElig = EligKey.of(allUsable);
        // After the graph, names and costs: a store that keeps planes in a file stamps them from all of these.
        this.legs = new Legs(map, graph, in.caches(), this::storeStamp);
        this.uniquePrereqs = uniq.keySet().toArray(new Prereq[0]);
        LinkedHashMap<Fact, Integer> factNumbers = new LinkedHashMap<>();
        this.prereqPrograms = new int[uniquePrereqs.length][];
        int longest = 1;
        for (int i = 0; i < uniquePrereqs.length; i++) {
            prereqPrograms[i] = uniquePrereqs[i].compile(f -> factNumbers.computeIfAbsent(f, k -> factNumbers.size()));
            longest = Math.max(longest, prereqPrograms[i].length);
        }
        this.longestProgram = longest;
        this.distinctFacts = factNumbers.keySet().toArray(new Fact[0]);
        // A gate that reads a fact negated wants it false, which the banked reading (true) would never give.
        Set<Fact> negated = new HashSet<>();
        for (Prereq p : uniquePrereqs) {
            negated.addAll(Arrays.asList(p.negatedFacts()));
        }
        IntArrayList relaxed = new IntArrayList();
        IntArrayList packRead = new IntArrayList();
        IntArrayList room = new IntArrayList();
        for (int i = 0; i < distinctFacts.length; i++) {
            if (distinctFacts[i].kind() == Fact.Kind.ROOM_FOR) {
                room.add(i);
            }
            if (distinctFacts[i].bankRelaxed() && !negated.contains(distinctFacts[i])) {
                relaxed.add(i);
            }
            if (distinctFacts[i].readsPack()) {
                packRead.add(i);
            }
        }
        this.bankRelaxedFacts = relaxed.toIntArray();
        this.packFacts = packRead.toIntArray();
        this.roomFacts = room.toIntArray();
        this.skillBreakpoints = SkillBreakpoints.of(distinctFacts);
        this.bankNodes = new int[graph.bankNodeCount()];
        this.bankCosts = new int[bankNodes.length];
        this.bankByName = new HashMap<>();
        this.bankNames = new HashSet<>();
        Map<Point, BankSite> siteAt = new HashMap<>();
        for (BankSite b : in.banks()) {
            siteAt.put(b.tile(), b);
            bankNames.add(b.name());
        }
        for (int i = 0; i < bankNodes.length; i++) {
            bankNodes[i] = graph.bankNode(i);
            BankSite site = siteAt.get(nodePoint(bankNodes[i]));
            if (site == null) {
                throw new IllegalStateException("bank node " + nodePoint(bankNodes[i]) + " is no bank's stand tile");
            }
            bankCosts[i] = site.cost();
            if (openrsc.nav.transports.AllTransports.declares(site.name()) || bankByName.put(site.name(), i) != null) {
                throw new IllegalStateException("bank name '" + site.name() + "' is not a unique cost-override key");
            }
        }
        long t0 = System.nanoTime();
        // Every field a metric can be customized under: each rung with avoidance on (dangerFor's key), and
        // with it off nothing is charged and the plain matrices apply. The topology keeps every arc they need.
        List<DangerField> fields = new ArrayList<>();
        for (int rung = 0; rung <= dangerMap.rungOf(Integer.MAX_VALUE); rung++) {
            int r = rung;
            fields.add(dangerFields.computeIfAbsent(r << 11 | 100, k -> new DangerField(dangerMap, r, 100)));
        }
        boolean[] gated = new boolean[transports.size()];
        for (int i = 0; i < gated.length; i++) {
            gated[i] = uniquePrereqs[prereqOf[i]].facts().length != 0;
        }
        this.topology = CchTopology.build(graph, fields, gated);
        this.searchTl = ThreadLocal.withInitial(() -> new CchSearch(topology));
        this.batchTl = ThreadLocal.withInitial(() -> new CchSearch(topology));
        this.bankedTl = ThreadLocal.withInitial(() -> new CchSearch(topology));
        this.walkFromTl = ThreadLocal.withInitial(() -> new CchSearch(topology));
        this.stepMasks = new StepMasks(map);
        this.batchRows = in.caches().retains() ? new AtomicReferenceArray<>(BATCH_ROWS) : null;
        this.metrics = in.caches().<MetricKey, CchMetric>weighed(NavStore.METRICS, CchMetric::bytes);
        this.goalLabels = in.caches().<LabelKey, Kept>weighed(NavStore.GOAL_LABELS, Kept::bytes);
        this.staticTargets = new StaticTargetTables(map);
        this.ownerTl = ThreadLocal.withInitial(() -> new int[topology.nodeCount]);
        this.graphShape = describeGraphShape();
        LOG.info(
                "nav: engine loaded — {} transports, {} unique prereqs, {} nodes, {} banks; {}; "
                        + "hierarchy in {}ms: {}",
                transports.size(),
                uniquePrereqs.length,
                graph.totalNodes(),
                bankNodes.length,
                graphShape,
                (System.nanoTime() - t0) / 1_000_000,
                topology.describe());
    }

    ClusterGraph graph() {
        return graph;
    }

    Legs legs() {
        return legs;
    }

    DangerMap dangerMap() {
        return dangerMap;
    }

    CchTopology topology() {
        return topology;
    }

    private String describeGraphShape() {
        int[] counts = new int[ClusterGeometry.TOTAL_CLUSTERS];
        int populated = 0;
        for (int c = 0; c < counts.length; c++) {
            counts[c] = graph.clusterNodeCount(c);
            if (counts[c] > 0) {
                populated++;
            }
        }
        int[] nonEmpty = new int[populated];
        for (int c = 0, i = 0; c < counts.length; c++) {
            if (counts[c] > 0) {
                nonEmpty[i++] = counts[c];
            }
        }
        Arrays.sort(nonEmpty);
        if (populated == 0) {
            return "clusters: none populated";
        }
        long total = 0;
        for (int v : nonEmpty) {
            total += v;
        }
        return String.format(
                "clusters: %d populated of %d, nodes/cluster mean=%.1f p50=%d p75=%d p90=%d p99=%d max=%d",
                populated,
                counts.length,
                (double) total / populated,
                nonEmpty[populated / 2],
                nonEmpty[populated * 3 / 4],
                nonEmpty[(int) (populated * 0.9)],
                nonEmpty[(int) (populated * 0.99)],
                nonEmpty[populated - 1]);
    }

    /**
     * One account's resolution under one policy: its facts, danger field, eligibility and costs, and
     * the routing classes they make. Its arrays are shared with every account of the class: never
     * write them.
     */
    static final class Resolved {
        AccountFacts account;
        NavPolicy policy;
        RouteContext facts;
        DangerField danger;
        private EligKey elig;
        boolean[] canUse;

        /** The banked class's vector, computed when the banked class is first asked for. */
        boolean[] canUseBanked;
        /**
         * The matched-prereq set that decided {@link #canUse}; null where overrides or the all-eligible probe
         * gave a vector of its own.
         */
        long[] matched;

        long[] factBits;

        int[] tCosts;
        private Klass klass;
        boolean bankedKnown;
        /** Whether a bank stop opens any transport the carried class refuses; known once {@link #bankedKnown}. */
        boolean relaxed;

        private Klass klassB;
        /** Whether banking only adds transports: every one the carried class may use, the banked class may too. */
        boolean bankedSuperset;
        /**
         * What decided the classes, read off the account; null unless its pack and bank are frozen and this
         * engine keeps things.
         */
        Inventory inventory;

        Bank bank;
        Quests quests;
        int[] levelRungs;

        CacheValues cacheValues;

        /** A new entry for {@code facts}' account whose inputs are this entry's: every class shared. */
        Resolved sharedWith(RouteContext facts) {
            Resolved r = new Resolved();
            r.account = facts.account();
            r.policy = facts.policy();
            r.facts = facts;
            r.danger = danger;
            r.elig = elig;
            r.canUse = canUse;
            r.canUseBanked = canUseBanked;
            r.matched = matched;
            r.factBits = factBits;
            r.tCosts = tCosts;
            r.klass = klass;
            r.bankedKnown = bankedKnown;
            r.relaxed = relaxed;
            r.klassB = klassB;
            r.bankedSuperset = bankedSuperset;
            r.inventory = inventory;
            r.bank = bank;
            r.quests = quests;
            r.levelRungs = levelRungs;
            r.cacheValues = cacheValues;
            return r;
        }
    }

    /** A thread's last few resolutions, by account: a thread serves many accounts in turn. */
    private static final class ResolveMemo {
        final Resolved[] slots = new Resolved[8];
        int next;
        Resolved current;
    }

    private final ThreadLocal<ResolveMemo> resolveTl = ThreadLocal.withInitial(ResolveMemo::new);

    /**
     * {@code account} resolved under {@code policy}: from this thread's memo when the same account, or
     * one whose frozen pack, bank and quests, level rungs and cache values are the same, was resolved
     * recently.
     */
    Resolved resolve(AccountFacts account, NavPolicy policy) {
        ResolveMemo memo = resolveTl.get();
        Resolved hit = memo.current;
        if (hit != null && hit.account == account && (hit.policy == policy || policy.equals(hit.policy))) {
            return hit;
        }
        for (Resolved s : memo.slots) {
            if (s != null && s.account == account && (s.policy == policy || policy.equals(s.policy))) {
                memo.current = s;
                return s;
            }
        }
        Inventory inventory = account.inventory();
        Bank bank = account.bank();
        // Frozen containers only, whose identity stands for their contents; and only in an engine that keeps
        // things, since a shared entry shares its class and so the metric the class holds.
        boolean keyed = batchRows != null && inventory instanceof Inventory.Frozen && bank instanceof Bank.Frozen;
        int[] levelRungs = keyed ? levelRungs(account.stats(), policy) : null;
        if (keyed) {
            for (int i = 0; i < memo.slots.length; i++) {
                Resolved s = memo.slots[i];
                if (s != null
                        && s.inventory == inventory
                        && s.bank == bank
                        && s.quests == account.quests()
                        && (s.policy == policy || policy.equals(s.policy))
                        && Arrays.equals(s.levelRungs, levelRungs)
                        && s.cacheValues.equals(account.cacheValues())) {
                    // A fresh entry holding this account: a caller up the stack may still hold the one it replaces.
                    Resolved r = s.sharedWith(new RouteContext(account, policy, serverData));
                    memo.slots[i] = r;
                    memo.current = r;
                    return r;
                }
            }
        }
        // A fresh entry, never a reused one: a caller up the stack may still hold the evicted entry.
        Resolved r = new Resolved();
        memo.slots[memo.next] = r;
        memo.next = (memo.next + 1) % memo.slots.length;
        memo.current = r;
        r.facts = new RouteContext(account, policy, serverData);
        r.canUseBanked = null;
        r.matched = null;
        r.factBits = null;
        r.danger = dangerFor(r.facts, policy);
        if (policy.assumeAllEligible()) {
            r.canUse = allEligible(policy);
            r.tCosts = policy.costOverrides().isEmpty() ? baseCosts : costsFor(policy, r.canUse);
            r.elig = EligKey.of(r.canUse);
        } else if (policy.costOverrides().isEmpty()) {
            r.factBits = factBits(r.facts);
            ClassEntry c = classOfFacts(r.factBits);
            r.matched = c.matched();
            r.canUse = c.canUse();
            r.tCosts = baseCosts;
            r.elig = c.elig();
        } else {
            r.factBits = factBits(r.facts);
            // An override can clear an entry (PROHIBITIVE), so this account's vector is a copy of its own.
            boolean[] canUse = classOfFacts(r.factBits).canUse().clone();
            r.tCosts = costsFor(policy, canUse);
            r.canUse = canUse;
            r.elig = EligKey.of(canUse);
        }
        r.klass = new Klass(this, r.canUse, r.tCosts, r.danger, r.elig);
        eligClassesSeen.putIfAbsent(r.elig, Boolean.TRUE);
        r.account = account;
        r.policy = policy;
        if (keyed) {
            r.inventory = inventory;
            r.bank = bank;
            r.quests = account.quests();
            r.levelRungs = levelRungs;
            r.cacheValues = account.cacheValues();
        }
        return r;
    }

    /**
     * Every transport usable by fiat, for {@link NavPolicy#assumeAllEligible}: the gates bypassed, bar
     * the ones the policy itself shuts.
     */
    private boolean[] allEligible(NavPolicy policy) {
        boolean[] all = new boolean[prereqOf.length];
        for (int i = 0; i < all.length; i++) {
            all[i] = !policyGated(policy, i);
        }
        return all;
    }

    /**
     * Whether {@code policy} itself shuts transport {@code i}, a teleport of a kind it disallows; no
     * bypass opens it.
     */
    private boolean policyGated(NavPolicy policy, int i) {
        boolean spellsShut = !policy.teleportSpells();
        boolean jewelleryShut = !policy.teleportJewellery();
        if (!spellsShut && !jewelleryShut) {
            return false;
        }
        EligibilityInputs inputs = uniquePrereqs[prereqOf[i]].inputs();
        return (spellsShut && inputs.anyFactOfKind(EligibilityInputs.Fact.Kind.TELEPORT_SPELLS_ALLOWED))
                || (jewelleryShut && inputs.anyFactOfKind(EligibilityInputs.Fact.Kind.TELEPORT_JEWELLERY_ALLOWED));
    }

    /**
     * Which transports a class may use, packed; held by value in a metric's key, so a collision cannot
     * share weights.
     */
    private record EligKey(long[] words, int hash) {

        static EligKey of(boolean[] canUse) {
            long[] w = new long[(canUse.length + 63) / 64];
            for (int i = 0; i < canUse.length; i++) {
                if (canUse[i]) {
                    w[i >> 6] |= 1L << (i & 63);
                }
            }
            return new EligKey(w, Arrays.hashCode(w));
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o == this || (o instanceof EligKey k && hash == k.hash && Arrays.equals(words, k.words));
        }
    }

    private record ClassEntry(long[] matched, boolean[] canUse, EligKey elig) {}

    /**
     * Classes by matched-prereq set, direct-mapped and bounded; a collision only rebuilds. It holds no
     * metric, so it keeps none alive past the metric store's budget.
     */
    private final AtomicReferenceArray<ClassEntry> classTable = new AtomicReferenceArray<>(1 << 12);

    private ClassEntry classFor(long[] matched) {
        int h = Arrays.hashCode(matched);
        int slot = (h ^ (h >>> 16)) & (classTable.length() - 1);
        ClassEntry e = classTable.get(slot);
        if (e != null && Arrays.equals(e.matched(), matched)) {
            return e;
        }
        boolean[] canUse = canUseFrom(matched);
        e = new ClassEntry(matched, canUse, EligKey.of(canUse));
        classTable.set(slot, e);
        return e;
    }

    private record FactEntry(long[] factBits, ClassEntry entry) {}

    /** Classes by fact bits, in front of {@link #classTable}: facts read as before run no prereq program. */
    private final AtomicReferenceArray<FactEntry> factTable = new AtomicReferenceArray<>(1 << 12);

    private ClassEntry classOfFacts(long[] factBits) {
        int h = Arrays.hashCode(factBits);
        int slot = (h ^ (h >>> 16)) & (factTable.length() - 1);
        FactEntry e = factTable.get(slot);
        if (e != null && Arrays.equals(e.factBits(), factBits)) {
            return e.entry();
        }
        ClassEntry c = classFor(matchedFrom(factBits));
        factTable.set(slot, new FactEntry(factBits, c));
        return c;
    }

    private long[] matchedPrereqsBanked(RouteContext facts) {
        return matchedFrom(bankedFactBits(facts, factBits(facts)));
    }

    /**
     * Every distinct fact's truth for {@code ctx}, bit {@code i} for {@code distinctFacts[i]}. All are
     * read, not just those a short-circuit would reach: a fact read is a total, effect-free function of
     * the frozen account.
     */
    private long[] factBits(RouteContext ctx) {
        long[] bits = new long[(distinctFacts.length + 63) >>> 6];
        for (int i = 0; i < distinctFacts.length; i++) {
            if (distinctFacts[i].test(ctx)) {
                bits[i >>> 6] |= 1L << i;
            }
        }
        return bits;
    }

    /** {@code carried} with the facts a bank stop can change read again as banked ({@link Fact#testBanked}). */
    private long[] bankedFactBits(RouteContext ctx, long[] carried) {
        long[] bits = carried.clone();
        for (int i : bankRelaxedFacts) {
            if (distinctFacts[i].testBanked(ctx)) {
                bits[i >>> 6] |= 1L << i;
            } else {
                bits[i >>> 6] &= ~(1L << i);
            }
        }
        return bits;
    }

    /** {@code carried} with the facts that read the pack read again over {@code settled}'s pack. */
    private long[] packFactBits(RouteContext settled, long[] carried) {
        long[] bits = carried.clone();
        for (int i : packFacts) {
            if (distinctFacts[i].test(settled)) {
                bits[i >>> 6] |= 1L << i;
            } else {
                bits[i >>> 6] &= ~(1L << i);
            }
        }
        return bits;
    }

    private long[] matchedFrom(long[] factBits) {
        long[] matched = new long[(uniquePrereqs.length + 63) / 64];
        boolean[] stack = new boolean[longestProgram];
        for (int i = 0; i < prereqPrograms.length; i++) {
            if (Prereq.run(prereqPrograms[i], factBits, stack)) {
                matched[i >> 6] |= 1L << (i & 63);
            }
        }
        return matched;
    }

    private boolean[] canUseFrom(long[] matched) {
        boolean[] e = new boolean[prereqOf.length];
        for (int i = 0; i < e.length; i++) {
            int p = prereqOf[i];
            e[i] = (matched[p >> 6] & (1L << (p & 63))) != 0;
        }
        return e;
    }

    /**
     * The transport costs {@code policy} prices by, shared: never write them. A
     * {@link NavPolicy#PROHIBITIVE} override clears {@code canUse} instead, and {@link NavPolicy#LIFTED}
     * sets it, where the policy itself does not shut the transport.
     *
     * @throws IllegalArgumentException when an override names no declared transport or bank, or prices
     *     a bank stop outside {@link BankSite#COST_RULE}
     */
    int[] costsFor(NavPolicy policy, boolean[] canUse) {
        Map<String, Integer> overrides = policy.costOverrides();
        if (overrides.isEmpty()) {
            return baseCosts;
        }
        return applied(overridesOf(overrides), canUse, policy);
    }

    private Overrides overridesOf(Map<String, Integer> overrides) {
        Overrides ov = overridesByMap.get(overrides);
        if (ov == null) {
            Map<String, Integer> key;
            try {
                // Map.copyOf refuses a null entry: such a map is resolved per ask and kept nowhere.
                key = Map.copyOf(overrides);
            } catch (NullPointerException nullEntry) {
                return resolveOverrides(overrides);
            }
            ov = resolveOverrides(key);
            if (overridesByMap.size() >= OVERRIDE_MAPS) {
                overridesByMap.clear();
            }
            Overrides raced = overridesByMap.putIfAbsent(key, ov);
            ov = raced != null ? raced : ov;
        }
        return ov;
    }

    private boolean lifted(NavPolicy policy, int tag) {
        if (policy.costOverrides().isEmpty() || policyGated(policy, tag)) {
            return false;
        }
        for (int i : overridesOf(policy.costOverrides()).lifted()) {
            if (i == tag) {
                return true;
            }
        }
        return false;
    }

    /**
     * A policy's overrides resolved: the costs (shared, never written) and their hash, the transports
     * prohibited and lifted, and the bank stops' costs ({@link #bankCosts} itself when none is re-priced).
     */
    private record Overrides(int[] costs, int hash, int[] prohibited, int[] lifted, int[] stops) {}

    private int[] stopCosts(NavPolicy policy) {
        return policy.costOverrides().isEmpty()
                ? bankCosts
                : overridesOf(policy.costOverrides()).stops();
    }

    private static long cheapestOf(int[] stops) {
        long min = Long.MAX_VALUE;
        for (int c : stops) {
            min = Math.min(min, c);
        }
        return min;
    }

    private final ConcurrentHashMap<Map<String, Integer>, Overrides> overridesByMap = new ConcurrentHashMap<>();

    private static final int OVERRIDE_MAPS = 64;

    private Overrides resolveOverrides(Map<String, Integer> overrides) {
        int[] costs = baseCosts.clone();
        IntArrayList prohibited = new IntArrayList();
        IntArrayList lifted = new IntArrayList();
        Set<String> unmatched = new HashSet<>(overrides.keySet());
        for (int i = 0; i < costs.length; i++) {
            // The most specific tier of Transport#policyKeys a policy names decides: a price there beats a
            // prohibit further out, a prohibit wins within the tier, and a lift never overrules a prohibit.
            String hit = null;
            boolean fenced = false;
            boolean fencedAnywhere = false;
            for (String[] tier : policyKeys[i]) {
                boolean deciding = hit == null;
                for (String key : tier) {
                    Integer v = overrides.get(key);
                    if (v == null) {
                        continue;
                    }
                    unmatched.remove(key);
                    boolean prohibit = v == NavPolicy.PROHIBITIVE;
                    fencedAnywhere |= prohibit;
                    if (deciding) {
                        hit = hit == null ? key : hit;
                        fenced |= prohibit;
                    }
                }
            }
            if (fenced) {
                prohibited.add(i);
            } else if (hit != null) {
                int o = overrides.get(hit);
                if (o == NavPolicy.LIFTED) {
                    if (fencedAnywhere) {
                        prohibited.add(i);
                    } else {
                        lifted.add(i);
                    }
                } else {
                    costs[i] = o;
                }
            }
        }
        // A bank's name re-prices a stop there: a bank is priced, never prohibited or lifted.
        int[] stops = bankCosts;
        for (Iterator<String> it = unmatched.iterator(); it.hasNext(); ) {
            String key = it.next();
            if (!bankNames.contains(key)) {
                continue;
            }
            it.remove();
            Integer o = overrides.get(key);
            if (o == null) {
                continue;
            }
            if (!BankSite.validCost(o)) {
                throw new IllegalArgumentException("cost override '" + key + "' = " + o + " — " + BankSite.COST_RULE);
            }
            Integer bank = bankByName.get(key);
            if (bank == null) {
                continue;
            }
            if (stops == bankCosts) {
                stops = bankCosts.clone();
            }
            stops[bank] = o;
        }
        // A declared transport this world lacks may be named; any other key is a typo.
        for (String key : unmatched) {
            if (!openrsc.nav.transports.AllTransports.declares(key)) {
                throw new IllegalArgumentException("cost override '" + key + "' names no transport");
            }
        }
        // Unchanged costs are the base array itself, which customized and fundedFloor recognise by identity.
        if (Arrays.equals(costs, baseCosts)) {
            return new Overrides(baseCosts, baseCostsHash, prohibited.toIntArray(), lifted.toIntArray(), stops);
        }
        return new Overrides(costs, Arrays.hashCode(costs), prohibited.toIntArray(), lifted.toIntArray(), stops);
    }

    private int[] applied(Overrides ov, boolean[] canUse, NavPolicy policy) {
        for (int i : ov.prohibited()) {
            canUse[i] = false;
        }
        for (int i : ov.lifted()) {
            canUse[i] = !policyGated(policy, i);
        }
        return ov.costs();
    }

    private int costsHash(int[] tCosts) {
        for (Overrides ov : overridesByMap.values()) {
            if (ov.costs() == tCosts) {
                return ov.hash();
            }
        }
        return Arrays.hashCode(tCosts);
    }

    private DangerField dangerFor(RouteContext facts, NavPolicy policy) {
        int scale = policy.chargesDanger(facts.account().stats()) ? 100 : 0;
        // By rung, not level: every bot between two thresholds shares one field. Nothing is charged with
        // a policy that spares the account, so there every bot is rung 0.
        int rung = scale == 0 ? 0 : dangerMap.rungOf(facts.account().stats().combatLevel());
        int key = (rung << 11) | scale;
        return dangerFields.computeIfAbsent(key, k -> new DangerField(dangerMap, rung, scale));
    }

    /** A chain's root. A non-negative tag is the index of the transport that reached its node. */
    static final int TAG_SEED = -1;
    /** A move inside a cluster. */
    static final int TAG_INTRA = -2;
    /** A step across a cluster boundary. */
    static final int TAG_BOUNDARY = -3;

    static final int STEP_COST = Legs.STEP_COST;

    /**
     * The weight a regional teleport carries on top of its cost: a hundred steps of walking, so a
     * teleport is chosen to save a real walk, not a few ticks of one. Weight only: a route that
     * teleports reports its true time.
     */
    static final int TELEPORT_WEIGHT = 100 * STEP_COST;

    /** Bumped when a change here can change what a persisted leg plane holds. */
    static final int PLANE_VERSION = 1;

    private byte[] storeStamp;

    /**
     * What every persisted leg plane is computed from. Data is hashed by content: the collision map, the
     * graph, the danger map, and each transport's name and base cost in order. Code is hashed by each
     * class's hand-bumped {@code PLANE_VERSION}. A store whose stamp differs starts empty. Computed on
     * first ask, so an engine whose stores live on the heap never pays.
     */
    private synchronized byte[] storeStamp() {
        if (storeStamp == null) {
            MessageDigest md = Digests.sha256();
            Digests.version(md, "Engine", PLANE_VERSION);
            Digests.version(md, "Legs", Legs.PLANE_VERSION);
            Digests.version(md, "DangerField", DangerField.PLANE_VERSION);
            Digests.version(md, "DangerMap", DangerMap.PLANE_VERSION);
            Digests.version(md, "ClusterEdges", ClusterEdges.PLANE_VERSION);
            Digests.version(md, "ClusterGeometry", ClusterGeometry.PLANE_VERSION);
            Digests.version(md, "CollisionMap", CollisionMap.STEP_RULES_VERSION);
            Digests.collisionMap(md, map);
            graph.digest(md);
            dangerMap.digest(md);
            Digests.ints(md, baseCosts);
            for (String name : names) {
                Digests.string(md, name);
            }
            storeStamp = md.digest();
        }
        return storeStamp;
    }

    /** A destination's price: the weight every choice inside it is made by, and the time reported for it. */
    private record Price(long weight, long time) {
        static final Price NONE = new Price(Long.MAX_VALUE, Long.MAX_VALUE);

        boolean beats(Price o) {
            return weight < o.weight || (weight == o.weight && time < o.time);
        }

        /** Deci-ticks, or -1 for no route: a weight without a duration counts as none. */
        int deci() {
            return weight == Long.MAX_VALUE || time >= Integer.MAX_VALUE ? -1 : (int) time;
        }
    }

    /**
     * One leg priced through the hierarchy: its weight, the time of the route the weight chose, and
     * the query that priced it, which {@link Engine#chain(Comp)} asks again only when the steps are wanted.
     */
    private static final class Comp {
        long cost = Long.MAX_VALUE;
        long time = Long.MAX_VALUE;

        Klass klass;
        /** Where the leg starts; null for a leg out of {@link #bank}. */
        Point fromTile;

        /** Where the leg ends; null for a leg into {@link #bank}. */
        Point goalTile;

        /** The bank a leg starts or ends at, as its index into {@code bankNodes}; -1 for none. */
        int bank = -1;
    }

    /**
     * Everything {@link CchMetric#customize} reads: eligibility and costs by value, so a hash collision
     * cannot price one class with another's weights, and the danger field by signature.
     */
    private record MetricKey(EligKey elig, int dangerSig, int[] tCosts, int hash) {

        static MetricKey of(EligKey elig, DangerField danger, int[] tCosts, int costsHash) {
            int h = (elig.hashCode() * 31 + danger.signature()) * 31 + costsHash;
            return new MetricKey(elig, danger.signature(), tCosts, h);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o == this
                    || (o instanceof MetricKey k
                            && hash == k.hash
                            && dangerSig == k.dangerSig
                            && elig.equals(k.elig)
                            && Arrays.equals(tCosts, k.tCosts));
        }
    }

    /**
     * One routing class, the transports usable at what cost under which danger field, and the metric
     * that prices it, customized on first use. Holds no engine: a thread's memos outlive the engine,
     * and a class that held one would keep it alive.
     */
    private static final class Klass {
        final boolean[] canUse;
        final int[] tCosts;
        final DangerField danger;
        final MetricKey key;
        /** The class this one derives from, an account's own for its banked class, whose metric it starts from. */
        final Klass parent;

        private CchMetric metric;

        Klass(Engine owner, boolean[] canUse, int[] tCosts, DangerField danger) {
            this(owner, canUse, tCosts, danger, EligKey.of(canUse), null);
        }

        Klass(Engine owner, boolean[] canUse, int[] tCosts, DangerField danger, Klass parent) {
            this(owner, canUse, tCosts, danger, EligKey.of(canUse), parent);
        }

        Klass(Engine owner, boolean[] canUse, int[] tCosts, DangerField danger, EligKey elig) {
            this(owner, canUse, tCosts, danger, elig, null);
        }

        Klass(Engine owner, boolean[] canUse, int[] tCosts, DangerField danger, EligKey elig, Klass parent) {
            this.canUse = canUse;
            this.tCosts = tCosts;
            this.danger = danger;
            this.parent = parent;
            this.key = MetricKey.of(
                    elig, danger, tCosts, tCosts == owner.baseCosts ? owner.baseCostsHash : owner.costsHash(tCosts));
        }

        CchMetric metric(Engine owner) {
            CchMetric m = metric;
            if (m == null) {
                m = owner.metrics.get(key, k -> {
                    owner.metricBuilds.incrementAndGet();
                    return owner.customized(danger, canUse, tCosts, parent);
                });
                metric = m;
            }
            return m;
        }

        /** Whether {@link #metric} answers without customizing. */
        boolean metricAtHand(Engine owner) {
            if (metric == null) {
                metric = owner.metrics.getIfPresent(key);
            }
            return metric != null;
        }
    }

    /**
     * Per danger field, the metric with every ungated transport as the field's first class has it and
     * every gated one closed: what {@link #customized} starts a class from. Kept outside the metric
     * store, since every class of the field needs it.
     */
    private final ConcurrentHashMap<Integer, CchMetric> baseMetrics = new ConcurrentHashMap<>();

    /**
     * A class's metric, as a delta where it can be: from its parent's metric when that is at hand, else
     * from its danger field's base when the class agrees with the base on every ungated transport (an
     * ungated transport reads no fact, so every class should), else whole. Every way gives the same
     * weights.
     */
    private CchMetric customized(DangerField danger, boolean[] canUse, int[] tCosts, Klass parent) {
        if (parent != null && parent.danger == danger && parent.tCosts == tCosts) {
            CchMetric from = parent.metric != null ? parent.metric : metrics.getIfPresent(parent.key);
            if (from != null) {
                int[] changed = new int[canUse.length];
                int n = 0;
                for (int i = 0; i < canUse.length; i++) {
                    if (canUse[i] != parent.canUse[i]) {
                        changed[n++] = i;
                    }
                }
                changedCustomizations.incrementAndGet();
                return CchMetric.customizeChanged(from, danger, canUse, tCosts, Arrays.copyOf(changed, n));
            }
        }
        if (tCosts == baseCosts) {
            CchMetric base = baseMetrics.computeIfAbsent(danger.signature(), sig -> {
                boolean[] constant = new boolean[canUse.length];
                for (int i = 0; i < constant.length; i++) {
                    constant[i] = !topology.gated[i] && canUse[i];
                }
                return CchMetric.customize(topology, danger, constant, baseCosts, false);
            });
            if (base.agreesUngated(canUse)) {
                boolean[] gated = topology.gated;
                int[] opened = new int[canUse.length];
                int n = 0;
                for (int i = 0; i < canUse.length; i++) {
                    if (gated[i] && canUse[i]) {
                        opened[n++] = i;
                    }
                }
                deltaCustomizations.incrementAndGet();
                return CchMetric.customizeChanged(base, danger, canUse, tCosts, Arrays.copyOf(opened, n));
            }
        }
        return CchMetric.customize(topology, danger, canUse, tCosts, false);
    }

    private final AtomicLong deltaCustomizations = new AtomicLong();
    private final AtomicLong changedCustomizations = new AtomicLong();
    private final AtomicLong fundedFloorSkips = new AtomicLong();
    private final AtomicLong bankedFloorSkips = new AtomicLong();
    private final AtomicLong batchRowHits = new AtomicLong();
    private final AtomicLong targetLabelBuilds = new AtomicLong();

    /**
     * These arrays' class: the thread's resolved account's own when they are its, so the shapes of one
     * plan share one key instead of hashing every transport again.
     */
    private Klass klassFor(boolean[] canUse, int[] tCosts, DangerField danger) {
        Resolved r = resolveTl.get().current;
        if (r != null
                && r.klass != null
                && r.danger == danger
                && r.tCosts == tCosts
                && Arrays.equals(r.canUse, canUse)) {
            return r.klass;
        }
        return new Klass(this, canUse, tCosts, danger);
    }

    private Klass bankedKlass(Resolved r, boolean[] canUseB, int[] tCostsB, DangerField danger) {
        Klass b = r.klassB;
        if (b != null && b.danger == danger && Arrays.equals(b.canUse, canUseB) && Arrays.equals(b.tCosts, tCostsB)) {
            return b;
        }
        Klass k = new Klass(this, canUseB, tCostsB, danger, r.klass);
        if (b == null) {
            r.klassB = k;
        }
        return k;
    }

    /**
     * The from-side of the funded shape, shared by every destination of a batch: whether a bank stop can
     * open anything, the banked class, and the cost of reaching each bank.
     */
    static final class FundedFrom {
        /** False when a bank stop opens no gate the pack cannot: the funded shape adds nothing. */
        boolean usable;

        int[] tCostsB;
        private Klass klassB;
        boolean bankedSuperset;
        boolean bankedRun;

        /** Per bank, the weight of reaching it plus its stop; {@code Long.MAX_VALUE} where it is out of reach. */
        long[] bankReach;

        int[] stops;
        /** Per bank, the packed cost of reaching it under the carried class; {@link CchMetric#INF} for none. */
        long[] toBank;
        /** The least of {@link #bankReach}: a floor under every funded weight from here, since the leg on only adds. */
        long cheapestStop = Long.MAX_VALUE;
    }

    /**
     * The funded shape's from-side for {@code from}, or an unusable one when it cannot help, which for a
     * destination behind no gate is decided by one scan. {@code fromSide}'s forward half must already
     * have run from {@code from} under {@code r}'s class.
     */
    FundedFrom fundedFrom(Point from, NavPolicy policy, Resolved r, CchSearch fromSide) {
        FundedFrom f = new FundedFrom();
        if (bankNodes.length == 0 || policy.assumeAllEligible()) {
            return f;
        }
        ensureBanked(r, policy);
        if (!r.relaxed) {
            return f;
        }
        f.tCostsB = r.klassB.tCosts;
        f.klassB = r.klassB;
        f.bankedSuperset = r.bankedSuperset;
        searches.incrementAndGet();
        // The leg to a bank is the walk the account can make now, under the carried class; only the leg on
        // may use the banked one.
        f.toBank = meetBanks(fromSide.forward, r.klass.metric(this).toBankLabels());
        f.stops = stopCosts(policy);
        f.bankReach = new long[bankNodes.length];
        for (int i = 0; i < bankNodes.length; i++) {
            f.bankReach[i] =
                    f.toBank[i] == CchMetric.INF ? Long.MAX_VALUE : CchMetric.weightOf(f.toBank[i]) + f.stops[i];
            f.cheapestStop = Math.min(f.cheapestStop, f.bankReach[i]);
        }
        f.usable = true;
        return f;
    }

    /** Settles {@code r}'s banked class once: whether it opens anything, the class, and whether it closes none. */
    private void ensureBanked(Resolved r, NavPolicy policy) {
        if (!r.bankedKnown) {
            if (r.matched != null) {
                // Every unique prereq gates some transport, so comparing the matched sets word by word answers both
                // opens and closes, and the banked class is shared like the carried one.
                ClassEntry banked = classOfFacts(bankedFactBits(r.facts, r.factBits));
                long[] matchedB = banked.matched();
                boolean opens = false;
                boolean closes = false;
                for (int w = 0; w < matchedB.length; w++) {
                    opens |= (matchedB[w] & ~r.matched[w]) != 0;
                    closes |= (r.matched[w] & ~matchedB[w]) != 0;
                }
                r.canUseBanked = banked.canUse();
                r.relaxed = opens;
                if (opens) {
                    r.klassB = new Klass(this, banked.canUse(), baseCosts, r.danger, banked.elig(), r.klass);
                    r.bankedSuperset = !closes;
                }
            } else {
                if (r.canUseBanked == null) {
                    r.canUseBanked = r.factBits == null
                            ? canUseFrom(matchedPrereqsBanked(r.facts))
                            : classOfFacts(bankedFactBits(r.facts, r.factBits)).canUse();
                }
                boolean[] canUseB = r.canUseBanked.clone();
                for (int i = 0; i < canUseB.length && !r.relaxed; i++) {
                    r.relaxed = canUseB[i] && !r.canUse[i];
                }
                if (r.relaxed) {
                    r.klassB = new Klass(this, canUseB, costsFor(policy, canUseB), r.danger, r.klass);
                    boolean superset = true;
                    for (int i = 0; i < r.canUse.length && superset; i++) {
                        superset = !r.canUse[i] || r.klassB.canUse[i];
                    }
                    r.bankedSuperset = superset;
                }
            }
            r.bankedKnown = true;
        }
    }

    /**
     * A goal priced through the best bank stop, {@code gB} its labels under the banked class: the
     * carried walk in, the stop, the banked way on. {@link Price#NONE} where no bank both reaches us and
     * opens the way.
     */
    private Price priceFunded(FundedFrom f, GoalLabels gB) {
        long[] backs = backsTo(gB, f.klassB);
        if (gB.ranks().length > 0) {
            searches.incrementAndGet();
        }
        long best = Long.MAX_VALUE;
        long bestTime = Long.MAX_VALUE;
        for (int i = 0; i < bankNodes.length; i++) {
            long to = f.toBank[i];
            long back = backs[i];
            if (to == CchMetric.INF || back == CchMetric.INF) {
                continue;
            }
            long total = (long) CchMetric.weightOf(to) + f.stops[i] + CchMetric.weightOf(back);
            long time = (to & 0xFFFFFFFFL) + f.stops[i] + (back & 0xFFFFFFFFL);
            if (total < best || (total == best && time < bestTime)) {
                best = total;
                bestTime = time;
            }
        }
        return new Price(best, bestTime);
    }

    /**
     * A floor under every weight {@link #priceFunded} can give: each bank's reach plus its way on with
     * every transport usable. {@code Long.MAX_VALUE} where no bank reaches the goal even so;
     * {@code Long.MIN_VALUE}, no floor, when the banked class's costs are not the base costs.
     */
    private long fundedFloor(Point goal, Resolved r, FundedFrom f) {
        if (f.tCostsB != baseCosts) {
            return Long.MIN_VALUE;
        }
        Klass open = new Klass(this, allUsable, baseCosts, r.danger, allUsableElig);
        GoalLabels g = goalLabels(goal, open);
        long[] backs = backsTo(g, open);
        if (g.ranks().length > 0) {
            searches.incrementAndGet();
        }
        long floor = Long.MAX_VALUE;
        for (int i = 0; i < backs.length; i++) {
            if (f.bankReach[i] != Long.MAX_VALUE && backs[i] != CchMetric.INF) {
                floor = Math.min(floor, f.bankReach[i] + CchMetric.weightOf(backs[i]));
            }
        }
        return floor;
    }

    /**
     * A floor under every funded weight: the goal direct under the banked class, plus the cheapest stop.
     * A funded route is a banked-class route through its bank as long as banking only adds transports;
     * {@code Long.MIN_VALUE}, no floor, where it closes any, {@code Long.MAX_VALUE} where the banked
     * class cannot reach the goal.
     */
    private long bankedDirectFloor(Point from, FundedFrom f, GoalLabels gB) {
        if (!f.bankedSuperset) {
            return Long.MIN_VALUE;
        }
        if (gB.ranks().length == 0) {
            return Long.MAX_VALUE;
        }
        CchSearch banked = bankedTl.get();
        if (!f.bankedRun) {
            runFromTile(banked.forward, from, f.klassB);
            f.bankedRun = true;
        }
        long p = meetLabels(banked.forward, gB);
        return p == CchMetric.INF ? Long.MAX_VALUE : CchMetric.weightOf(p) + cheapestOf(f.stops);
    }

    /**
     * Seeds {@code half} at a tile: its walk to each node of its cluster, and each cast source whose
     * region holds it.
     */
    private void seedFromTile(CchSearch.Half half, Point from, DangerField danger) {
        half.reset();
        int sc = ClusterGeometry.clusterOf(from.x(), from.y());
        int first = graph.clusterFirstNode(sc);
        int[] times = new int[graph.clusterNodeCount(sc)];
        int[] costs = legs.costsAt(sc, from.x(), from.y(), true, danger, times);
        for (int m = 0; m < costs.length; m++) {
            if (costs[m] != Integer.MAX_VALUE) {
                half.seed(first + m, CchMetric.pack(costs[m], times[m]));
            }
        }
        for (int src = 0; src < graph.castSourceCount(); src++) {
            if (graph.castSourceRegion(src).contains(from.x(), from.y())) {
                half.seed(topology.firstSource + src, 0L);
            }
        }
    }

    /** {@code half} run from {@code from} under {@code k}, unless it still holds exactly that run. */
    private void runFromTile(CchSearch.Half half, Point from, Klass k) {
        CchMetric m = k.metric(this);
        if (half.holds(m, from)) {
            return;
        }
        seedFromTile(half, from, k.danger);
        half.run(m);
        half.hold(m, from);
    }

    /** Seeds {@code half} at a goal tile: each node's walk to it. False for a goal that is fully blocked. */
    private boolean seedToTile(CchSearch.Half half, Point goal, DangerField danger) {
        half.reset();
        int gc = ClusterGeometry.clusterOf(goal.x(), goal.y());
        int[] times = new int[graph.clusterNodeCount(gc)];
        int[] seeds = goalSeeds(goal, gc, danger, times);
        if (seeds == null) {
            return false;
        }
        int first = graph.clusterFirstNode(gc);
        for (int j = 0; j < seeds.length; j++) {
            if (seeds[j] != Integer.MAX_VALUE) {
                half.seed(first + j, CchMetric.pack(seeds[j], times[j]));
            }
        }
        return true;
    }

    private Comp tileToGoal(Point from, Point goal, Klass k) {
        CchSearch s = searchTl.get();
        GoalLabels g = goalLabels(goal, k);
        if (g.ranks().length > 0) {
            runFromTile(s.forward, from, k);
        }
        return toGoal(s, from, goal, k, g);
    }

    private Comp toGoal(CchSearch fromSide, Point from, Point goal, Klass k, GoalLabels g) {
        Comp c;
        if (g.ranks().length == 0) {
            c = new Comp();
            c.klass = k;
        } else {
            c = pricedAt(meetLabels(fromSide.forward, g), k);
        }
        c.fromTile = from;
        c.goalTile = goal;
        return c;
    }

    /** The cheapest route from {@code forward}'s seeds to {@code g}'s goal, packed; {@link CchMetric#INF} for none. */
    private long meetLabels(CchSearch.Half forward, GoalLabels g) {
        searches.incrementAndGet();
        long best = CchMetric.INF;
        int[] ranks = g.ranks();
        long[] costs = g.costs();
        for (int i = 0; i < ranks.length; i++) {
            int r = ranks[i];
            if (forward.reached(r)) {
                long via = forward.dist[r] + costs[i];
                if (via < best) {
                    best = via;
                }
            }
        }
        return best;
    }

    /** What {@link #goalLabels} keeps values under: one store, one budget, for values of one kind. */
    private sealed interface LabelKey permits GoalKey, TargetsKey, SeedsKey, AreaKey {}

    private sealed interface Kept permits GoalLabels, TargetSeeds {
        long bytes();
    }

    private record GoalKey(long tile, MetricKey metric) implements LabelKey {
        @Override
        public int hashCode() {
            // Spelled out: the store asks on every price.
            return Long.hashCode(tile) * 31 + metric.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return o == this || (o instanceof GoalKey k && tile == k.tile && metric.equals(k.metric));
        }
    }

    /**
     * What a backward half from a goal reaches under one class: its ranks ascending, each one's cost to
     * the goal and the edge that set it, so a chain to the goal walks these instead of searching again.
     * Empty for a blocked goal. {@code fromBanks[0]} holds, once asked, every bank's cost to the goal
     * ({@link #backsTo}), whose weight {@code banks} reserves; {@code owners}, for a set of goals, names
     * the goal a seed label stands for.
     */
    private record GoalLabels(int[] ranks, long[] costs, int[] preds, int banks, long[][] fromBanks, int[] owners)
            implements Kept {
        static final GoalLabels NONE = new GoalLabels(new int[0], new long[0], new int[0], 0, new long[1][], null);

        @Override
        public long bytes() {
            return 64L
                    + ranks.length * (long) (2 * Integer.BYTES + Long.BYTES)
                    + (ranks.length == 0 ? 0 : 32L + (long) banks * Long.BYTES)
                    + (owners == null ? 0 : 16L + owners.length * (long) Integer.BYTES);
        }
    }

    private GoalLabels goalLabels(Point goal, Klass k) {
        GoalKey key = new GoalKey((long) goal.y() * CollisionMap.WIDTH + goal.x(), k.key);
        Kept held = goalLabels.getIfPresent(key);
        return (GoalLabels) (held != null ? held : goalLabels.get(key, _ -> computeGoalLabels(goal, k)));
    }

    private GoalLabels computeGoalLabels(Point goal, Klass k) {
        {
            CchSearch s = searchTl.get();
            if (!seedToTile(s.backward, goal, k.danger)) {
                return GoalLabels.NONE;
            }
            s.backward.run(k.metric(this));
            int n = s.backward.size();
            int[] ranks = new int[n];
            long[] costs = new long[n];
            int[] preds = new int[n];
            int kept = 0;
            for (int i = 0; i < n; i++) {
                int r = s.backward.rankAt(i);
                long d = s.backward.dist[r];
                if (d != CchMetric.INF) {
                    ranks[kept] = r;
                    preds[kept] = s.backward.pred[r];
                    costs[kept++] = d;
                }
            }
            return kept == 0
                    ? GoalLabels.NONE
                    : new GoalLabels(
                            Arrays.copyOf(ranks, kept),
                            Arrays.copyOf(costs, kept),
                            Arrays.copyOf(preds, kept),
                            bankNodes.length,
                            new long[1][],
                            null);
        }
    }

    /** Tile to every bank, index-aligned with {@code bankNodes}. */
    private Comp[] tileToBanks(Point from, Klass k) {
        CchSearch s = searchTl.get();
        runFromTile(s.forward, from, k);
        return banksFrom(s, from, k);
    }

    private Comp[] banksFrom(CchSearch fromSide, Point from, Klass k) {
        searches.incrementAndGet();
        long[] best = meetBanks(fromSide.forward, k.metric(this).toBankLabels());
        Comp[] out = new Comp[bankNodes.length];
        for (int i = 0; i < bankNodes.length; i++) {
            out[i] = pricedAt(best[i], k);
            out[i].fromTile = from;

            out[i].bank = i;
        }
        return out;
    }

    private long[] meetBanks(CchSearch.Half half, long[] labels) {
        int banks = bankNodes.length;
        long[] best = new long[banks];
        Arrays.fill(best, CchMetric.INF);
        int[] slotOf = topology.bankSlot;
        for (int j = 0; j < half.size(); j++) {
            int r = half.rankAt(j);
            int slot = slotOf[r];
            long d = half.dist[r];
            if (slot < 0 || d == CchMetric.INF) {
                continue;
            }
            int base = slot * banks;
            for (int i = 0; i < banks; i++) {
                long b = labels[base + i];
                if (b != CchMetric.INF && d + b < best[i]) {
                    best[i] = d + b;
                }
            }
        }
        return best;
    }

    private static Comp pricedAt(long packed, Klass k) {
        Comp c = new Comp();
        c.klass = k;
        if (packed != CchMetric.INF) {
            c.cost = CchMetric.weightOf(packed);
            c.time = packed & 0xFFFFFFFFL;
        }
        return c;
    }

    /** Every bank to a goal tile, index-aligned with {@code bankNodes}; all unpriced for a blocked goal. */
    private Comp[] banksToGoal(Point goal, Klass k) {
        GoalLabels g = goalLabels(goal, k);
        long[] best = backsTo(g, k);
        if (g.ranks().length > 0) {
            searches.incrementAndGet();
        }
        Comp[] out = new Comp[bankNodes.length];
        for (int i = 0; i < bankNodes.length; i++) {
            out[i] = pricedAt(best[i], k);

            out[i].goalTile = goal;
            out[i].bank = i;
        }
        return out;
    }

    /**
     * Per bank, the packed cost from it to {@code g}'s goal under {@code k}, {@link CchMetric#INF} for
     * none: kept on {@code g}, never written.
     */
    private long[] backsTo(GoalLabels g, Klass k) {
        long[] held = g.fromBanks()[0];
        if (held != null) {
            return held;
        }
        int banks = bankNodes.length;
        long[] best = new long[banks];
        Arrays.fill(best, CchMetric.INF);
        int[] ranks = g.ranks();
        // NONE is shared by every blocked goal: nothing is kept on it.
        if (ranks.length == 0) {
            return best;
        }
        long[] labels = k.metric(this).fromBankLabels();
        int[] slotOf = topology.bankSlot;
        long[] costs = g.costs();
        for (int j = 0; j < ranks.length; j++) {
            int slot = slotOf[ranks[j]];
            if (slot < 0) {
                continue;
            }
            long d = costs[j];
            int base = slot * banks;
            for (int i = 0; i < banks; i++) {
                long via = labels[base + i] + d;
                if (via < best[i]) {
                    best[i] = via;
                }
            }
        }
        g.fromBanks()[0] = best;
        return best;
    }

    /**
     * A priced leg's chain ({@link CchSearch#chain}), read out of the query that priced it; null if it
     * never priced.
     */
    private List<int[]> chain(Comp c) {
        return chain(c, searchTl.get());
    }

    private List<int[]> chain(Comp c, CchSearch s) {
        if (c.cost == Long.MAX_VALUE) {
            return null;
        }
        CchMetric m = c.klass.metric(this);
        if (c.fromTile == null) {
            // Out of a bank: both sides are kept labels, so no half runs and the thread's tile half stays as it was.
            GoalLabels g = goalLabels(c.goalTile, c.klass);
            return s.chainFromBank(m, c.bank, g.ranks(), g.costs(), g.preds());
        }
        runFromTile(s.forward, c.fromTile, c.klass);
        if (c.goalTile != null) {
            GoalLabels g = goalLabels(c.goalTile, c.klass);
            return s.chainToLabels(m, g.ranks(), g.costs(), g.preds());
        }
        return s.chainToBank(m, c.bank);
    }

    private final ConcurrentHashMap<EligKey, Boolean> eligClassesSeen = new ConcurrentHashMap<>();

    @Override
    public long[] workCounters() {
        return new long[] {metricBuilds.get(), legs.planeBuilds(), legs.sweeps(), searches.get()};
    }

    @Override
    public int[] classCensus(Collection<? extends AccountFacts> fleet, NavPolicy policy) {
        Map<MetricKey, Integer> counts = new HashMap<>();
        for (AccountFacts account : fleet) {
            counts.merge(resolve(account, policy).klass.key, 1, Integer::sum);
        }
        return counts.values().stream()
                .mapToInt(Integer::intValue)
                .boxed()
                .sorted(Comparator.reverseOrder())
                .mapToInt(Integer::intValue)
                .toArray();
    }

    @Override
    public int warmClasses(Collection<? extends AccountFacts> fleet, NavPolicy policy) {
        // One account per class, chosen serially: resolve memoizes per thread.
        Map<MetricKey, AccountFacts> byKey = new LinkedHashMap<>();
        for (AccountFacts account : fleet) {
            byKey.putIfAbsent(resolve(account, policy).klass.key, account);
        }
        byKey.values().parallelStream()
                .forEach(account -> resolve(account, policy).klass.metric(this));

        // And the walk legs of the cluster each account stands in, which every plan from there reads.
        Set<Long> legKeys = ConcurrentHashMap.newKeySet();
        fleet.parallelStream().forEach(account -> {
            if (!account.pos().known()) {
                return;
            }
            DangerField d = resolve(account, policy).danger;
            int cluster =
                    ClusterGeometry.clusterOf(account.pos().x(), account.pos().y());
            if (cluster < 0 || !legKeys.add((long) cluster << 32 | (d.viewSignature(cluster) & 0xFFFFFFFFL))) {
                return;
            }
            legs.toNodes(cluster, d);
        });

        LOG.info("nav: warmed {} routing classes, {} cluster legs", byKey.size(), legKeys.size());
        return byKey.size();
    }

    @Override
    public String cacheStats() {
        NavCacheStats s = metrics.stats();
        return String.format(
                "metrics: %d entries, %dMB held, hitRate=%.3f, loads=%d, missCost=%.2fms, "
                        + "evictionWeight=%dMB, deltas=%d, fromParent=%d, fundedSkips=%d, "
                        + "bankedSkips=%d, fundedResearches=%d (capped %d), batchRowHits=%d, "
                        + "classRowHits=%d, planHits=%d, planStopSkips=%d; "
                        + "%s; searches=%d; eligClasses=%d; dangerFields=%d; %s",
                s.entries(),
                s.heldBytes() >> 20,
                s.hitRate(),
                s.loads(),
                s.missCostMs(),
                s.evictedBytes() >> 20,
                deltaCustomizations.get(),
                changedCustomizations.get(),
                fundedFloorSkips.get(),
                bankedFloorSkips.get(),
                fundedResearches.get(),
                fundedResearchCapped.get(),
                batchRowHits.get(),
                classRowHits.get(),
                planHits.get(),
                planStopSkips.get(),
                legs.stats(),
                searches.get(),
                eligClassesSeen.size(),
                dangerFields.size(),
                graphShape);
    }

    @Override
    public int dangerAt(AccountFacts account, NavPolicy policy, int x, int y) {
        return resolve(account, policy).danger.rawPenaltyAt(x, y);
    }

    @Override
    public DangerCharge dangerCharge(AccountFacts account, NavPolicy policy) {
        DangerField danger = resolve(account, policy).danger;
        return danger.anyDanger() ? danger::penaltyAt : DangerCharge.NONE;
    }

    @Override
    public int dangerCeiling() {
        return DangerMap.TILE_CAP;
    }

    @Override
    public TravelTime estimate(AccountFacts account, Point from, Dest dest, NavPolicy policy) {
        // A batch of one: a single estimate is exactly its batch's answer.
        return TravelTime.ofDeci(estimateDeci(account, from, List.of(dest), policy)[0]);
    }

    @Override
    public TravelTimes estimateBatch(AccountFacts account, Point from, List<? extends Dest> dests, NavPolicy policy) {
        return TravelTimes.ofDeci(estimateDeci(account, from, dests, policy));
    }

    /** {@code estimateBatch} in deci-ticks, -1 for no route, in a fresh array the caller owns. */
    private int[] estimateDeci(AccountFacts account, Point from, List<? extends Dest> dests, NavPolicy policy) {
        int[] out = new int[dests.size()];
        Arrays.fill(out, -1);
        if (!map.inBounds(from.x(), from.y())) {
            return out;
        }
        // The same batch from the same inputs is a lookup; the account is not even resolved.
        BatchKey key = batchKey(account, from, dests, policy);
        int slot = 0;
        if (key != null) {
            slot = (key.hash() ^ (key.hash() >>> 16)) & (BATCH_ROWS - 1);
            BatchRow row = batchRows.get(slot);
            if (row != null && row.key().equals(key)) {
                batchRowHits.incrementAndGet();
                return row.answers().clone();
            }
        }
        Resolved r = resolve(account, policy);
        // A batch is a function of the account's classes, not its pack: a pack that changes with every ore
        // still finds its batch here.
        ClassBatchKey classKey = null;
        int classSlot = 0;
        if (key != null) {
            classKey = classBatchKey(from, dests, policy, r);
            classSlot = (classKey.hash() ^ (classKey.hash() >>> 16)) & (BATCH_ROWS - 1);
            ClassRow row = classRows.get(classSlot);
            if (row != null && row.key().equals(classKey)) {
                classRowHits.incrementAndGet();
                keepRow(slot, key, dests, row.answers());
                return row.answers().clone();
            }
        }
        // One from-side for the whole batch: each destination pays only its own backward half.
        CchSearch fromSide = batchTl.get();
        runFromTile(fromSide.forward, from, r.klass);
        FundedFrom funded = fundedFrom(from, policy, r, fromSide);
        for (int i = 0; i < out.length; i++) {
            out[i] = priceDest(from, dests.get(i), policy, r, funded, fromSide).deci();
        }
        if (key != null) {
            int[] kept = out.clone();
            keepRow(slot, key, dests, kept);
            classRows.set(
                    classSlot,
                    new ClassRow(
                            new ClassBatchKey(
                                    classKey.from(),
                                    classKey.carried(),
                                    classKey.banked(),
                                    classKey.superset(),
                                    classKey.policy(),
                                    List.copyOf(dests),
                                    classKey.hash()),
                            kept));
        }
        return out;
    }

    @Override
    public @Nullable Nearest nearest(
            AccountFacts account, Point from, List<? extends Dest> live, StaticTargets statics, NavPolicy policy) {
        if (!map.inBounds(from.x(), from.y())) {
            return null;
        }
        // A destination's price is the lightest of its local walk, its hierarchy route and its funded route,
        // so the least over many is the least of three minima, each found at once: the local field read at
        // every nearby engage tile, one backward half seeded from every candidate, and the funded shape met
        // against the candidates' banked labels. The winner alone is then priced as any destination is.
        StaticTargetTables.Targets ts = staticTargets.select(statics);
        Resolved r = resolve(account, policy);
        CchSearch fromSide = batchTl.get();
        runFromTile(fromSide.forward, from, r.klass);
        FundedFrom funded = fundedFrom(from, policy, r, fromSide);
        IntArrayList near = new IntArrayList();
        int reach = -1;
        Point far = null;
        if (!ts.isEmpty()) {
            int[] widest = {-1, -1};
            ts.forEachNear(from, WindowSearch.LOCAL_RADIUS_MAX, g -> {
                boolean any = false;
                for (int tile : ts.engage(g)) {
                    int tx = tile % CollisionMap.WIDTH, ty = tile / CollisionMap.WIDTH;
                    if (LocalField.radiusFor(from, Point.of(tx, ty)) < 0) {
                        continue;
                    }
                    any = true;
                    int d = Point.chebyshev(from.x(), from.y(), tx, ty);
                    if (d > widest[0]) {
                        widest[0] = d;
                        widest[1] = tile;
                    }
                }
                if (any) {
                    near.add(g);
                }
            });
            reach = widest[0];
            if (reach >= 0) {
                far = Point.of(widest[1] % CollisionMap.WIDTH, widest[1] / CollisionMap.WIDTH);
            }
        }
        LocalField local = far == null ? null : localField(from, far, r.danger);

        Price best = Price.NONE;
        int bestLive = -1;
        for (int i = 0; i < live.size(); i++) {
            Price p = priceDest(from, live.get(i), policy, r, funded, fromSide);
            if (p.deci() >= 0 && p.beats(best)) {
                best = p;
                bestLive = i;
            }
        }
        Price lead = Price.NONE;
        int winner = -1;
        if (!ts.isEmpty()) {
            if (local != null) {
                for (int k = 0; k < near.size(); k++) {
                    int g = near.getInt(k);
                    for (int tile : ts.engage(g)) {
                        int tx = tile % CollisionMap.WIDTH, ty = tile / CollisionMap.WIDTH;
                        if (LocalField.radiusFor(from, Point.of(tx, ty)) < 0) {
                            continue;
                        }
                        int w = local.weight(tx, ty);
                        if (w != Integer.MAX_VALUE) {
                            Price p = new Price(w, local.time(tx, ty));
                            if (p.beats(lead)) {
                                lead = p;
                                winner = g;
                            }
                        }
                    }
                }
            }
            GoalLabels labels = targetLabels(ts, r.klass);
            int at = meetIndex(fromSide.forward, labels);
            if (at >= 0) {
                long p = fromSide.forward.dist[labels.ranks()[at]] + labels.costs()[at];
                Price via = new Price(CchMetric.weightOf(p), p & 0xFFFFFFFFL);
                if (via.beats(lead)) {
                    lead = via;
                    winner = ownerAt(labels, at);
                }
            }
            // No funded weight is under the cheapest stop: the banked labels matter only when it could undercut.
            if (funded.usable && funded.cheapestStop <= Math.min(best.weight(), lead.weight())) {
                GoalLabels banked = targetLabels(ts, funded.klassB);
                if (banked.ranks().length > 0) {
                    Price via = priceFunded(funded, banked);
                    if (via.deci() >= 0 && via.beats(lead)) {
                        lead = via;
                        winner = fundedOwner(funded, banked);
                    }
                }
            }
        }
        if (winner >= 0 && best.weight() >= lead.weight()) {
            Price exact = priceDest(
                    from,
                    winningTiles(ts.engage(winner), lead, from, r, funded, fromSide),
                    policy,
                    r,
                    funded,
                    fromSide);
            if (exact.deci() >= 0 && exact.beats(best)) {
                return ts.nearest(winner, TravelTime.ofDeci(exact.deci()));
            }
        }
        return bestLive < 0 ? null : new Nearest(bestLive, -1, -1, -1, -1, TravelTime.ofDeci(best.deci()));
    }

    /**
     * The winner's tiles that can still carry its price: those whose direct weight is the winning one.
     * Where a bank stop could undercut the win, every tile stays.
     */
    private Dest winningTiles(int[] tiles, Price lead, Point from, Resolved r, FundedFrom funded, CchSearch fromSide) {
        if (funded.usable && funded.cheapestStop <= lead.weight()) {
            return StaticTargetTables.Targets.dest(tiles);
        }
        IntArrayList keep = new IntArrayList();
        for (int tile : tiles) {
            Point goal = Point.of(tile % CollisionMap.WIDTH, tile / CollisionMap.WIDTH);
            long w = Long.MAX_VALUE;
            LocalField local = localField(from, goal, r.danger);
            if (local != null && local.weight(goal.x(), goal.y()) != Integer.MAX_VALUE) {
                w = local.weight(goal.x(), goal.y());
            }
            GoalLabels g = goalLabels(goal, r.klass);
            if (g.ranks().length > 0) {
                long p = meetLabels(fromSide.forward, g);
                if (p != CchMetric.INF) {
                    w = Math.min(w, CchMetric.weightOf(p));
                }
            }
            if (w == lead.weight()) {
                keep.add(tile);
            }
        }
        return StaticTargetTables.Targets.dest(keep.isEmpty() ? tiles : keep.toIntArray());
    }

    private record TargetsKey(int kind, int[] ids, int[] excluded, MetricKey metric, int hash) implements LabelKey {

        TargetsKey(int kind, int[] ids, int[] excluded, MetricKey metric) {
            this(
                    kind,
                    ids,
                    excluded,
                    metric,
                    ((kind * 31 + Arrays.hashCode(ids)) * 31 + Arrays.hashCode(excluded)) * 31 + metric.hashCode());
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o == this
                    || (o instanceof TargetsKey k
                            && hash == k.hash
                            && kind == k.kind
                            && Arrays.equals(ids, k.ids)
                            && Arrays.equals(excluded, k.excluded)
                            && metric.equals(k.metric));
        }
    }

    private GoalLabels targetLabels(StaticTargetTables.Targets ts, Klass k) {
        TargetsKey key = new TargetsKey(ts.kind().ordinal(), ts.ids(), ts.excluded(), k.key);
        Kept held = goalLabels.getIfPresent(key);
        if (held != null) {
            return (GoalLabels) held;
        }
        // Looked up before the load, not inside it: a Caffeine store refuses a load that loads from itself.
        TargetSeeds seeds = targetSeeds(ts, k.danger);
        return (GoalLabels) goalLabels.get(key, _ -> computeTargetLabels(ts, k, seeds));
    }

    /**
     * One backward half seeded from every engage tile of every candidate, each node keeping its
     * lightest seed: each label is the cost to the nearest candidate, and its seed's owner names which.
     * Min-plus is linear, so meeting these is meeting each candidate's own labels and keeping the least.
     */
    private GoalLabels computeTargetLabels(StaticTargetTables.Targets ts, Klass k, TargetSeeds all) {
        searches.incrementAndGet();
        targetLabelBuilds.incrementAndGet();
        CchSearch.Half half = searchTl.get().backward;
        half.reset();
        int[] owner = ownerTl.get();
        boolean seeded = false;
        // The clusters holding an excepted candidate's tile are reduced again without it.
        IntOpenHashSet dirty = new IntOpenHashSet();
        for (int g : ts.excluded()) {
            for (int tile : ts.engage(g)) {
                dirty.add(ClusterGeometry.clusterOf(tile % CollisionMap.WIDTH, tile / CollisionMap.WIDTH));
            }
        }
        for (int c = 0; c < all.clusters().length; c++) {
            int gc = all.clusters()[c];
            long[] cost = all.cost()[c];
            int[] from = all.owner()[c];
            if (dirty.contains(gc)) {
                cost = new long[cost.length];
                from = new int[cost.length];
                reduceSeeds(gc, all.tiles()[c], ts, k.danger, cost, from);
            }
            int first = graph.clusterFirstNode(gc);
            for (int m = 0; m < cost.length; m++) {
                if (cost[m] != CchMetric.INF && half.seed(first + m, cost[m])) {
                    owner[topology.rankOf[first + m]] = from[m];
                    seeded = true;
                }
            }
        }
        if (!seeded) {
            return GoalLabels.NONE;
        }
        half.run(k.metric(this));
        int n = half.size();
        int[] ranks = new int[n];
        long[] costs = new long[n];
        int[] preds = new int[n];
        int[] owners = new int[n];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            int r = half.rankAt(i);
            long d = half.dist[r];
            if (d != CchMetric.INF) {
                ranks[kept] = r;
                preds[kept] = half.pred[r];
                owners[kept] = half.pred[r] < 0 ? owner[r] : -1;
                costs[kept++] = d;
            }
        }
        return new GoalLabels(
                Arrays.copyOf(ranks, kept),
                Arrays.copyOf(costs, kept),
                Arrays.copyOf(preds, kept),
                bankNodes.length,
                new long[1][],
                Arrays.copyOf(owners, kept));
    }

    private record SeedsKey(int kind, int[] ids, int dangerSig, int hash) implements LabelKey {

        SeedsKey(int kind, int[] ids, int dangerSig) {
            this(kind, ids, dangerSig, (kind * 31 + Arrays.hashCode(ids)) * 31 + dangerSig);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o == this
                    || (o instanceof SeedsKey k
                            && hash == k.hash
                            && kind == k.kind
                            && dangerSig == k.dangerSig
                            && Arrays.equals(ids, k.ids));
        }
    }

    /**
     * A target set's seeds reduced per cluster: on each node of each cluster an engage tile lies in, the
     * least walk cost over those tiles and its candidate. {@code tiles[c]} keeps cluster
     * {@code clusters[c]}'s (candidate, tile) pairs in seeding order, so it can be reduced again without
     * some of them.
     */
    private record TargetSeeds(int[] clusters, long[][] cost, int[][] owner, int[][] tiles) implements Kept {
        @Override
        public long bytes() {
            long b = 64L + clusters.length * 16L;
            for (int c = 0; c < clusters.length; c++) {
                b += 48L
                        + cost[c].length * (long) (Long.BYTES + Integer.BYTES)
                        + tiles[c].length * (long) Integer.BYTES;
            }
            return b;
        }
    }

    private TargetSeeds targetSeeds(StaticTargetTables.Targets ts, DangerField danger) {
        SeedsKey key = new SeedsKey(ts.kind().ordinal(), ts.ids(), danger.signature());
        Kept held = goalLabels.getIfPresent(key);
        return (TargetSeeds) (held != null ? held : goalLabels.get(key, _ -> computeTargetSeeds(ts, danger)));
    }

    private TargetSeeds computeTargetSeeds(StaticTargetTables.Targets ts, DangerField danger) {
        it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap<IntArrayList> byCluster =
                new it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap<>();
        ts.forEachOfIds(g -> {
            for (int tile : ts.engage(g)) {
                int gc = ClusterGeometry.clusterOf(tile % CollisionMap.WIDTH, tile / CollisionMap.WIDTH);
                IntArrayList pairs = byCluster.computeIfAbsent(gc, _ -> new IntArrayList());
                pairs.add(g);
                pairs.add(tile);
            }
        });
        int n = byCluster.size();
        int[] clusters = new int[n];
        long[][] cost = new long[n][];
        int[][] owner = new int[n][];
        int[][] tiles = new int[n][];
        int c = 0;
        for (var e : byCluster.int2ObjectEntrySet()) {
            int gc = e.getIntKey();
            clusters[c] = gc;
            tiles[c] = e.getValue().toIntArray();
            cost[c] = new long[graph.clusterNodeCount(gc)];
            owner[c] = new int[cost[c].length];
            reduceSeeds(gc, tiles[c], null, danger, cost[c], owner[c]);
            c++;
        }
        return new TargetSeeds(clusters, cost, owner, tiles);
    }

    /**
     * Cluster {@code gc}'s least seed per node over {@code pairs}, less what {@code except} excepts; ties
     * keep the first.
     */
    private void reduceSeeds(
            int gc,
            int[] pairs,
            StaticTargetTables.@Nullable Targets except,
            DangerField danger,
            long[] cost,
            int[] owner) {
        Arrays.fill(cost, CchMetric.INF);
        Arrays.fill(owner, -1);
        int[] times = new int[cost.length];
        for (int i = 0; i < pairs.length; i += 2) {
            int g = pairs[i];
            if (except != null && except.isExcluded(g)) {
                continue;
            }
            int tile = pairs[i + 1];
            int[] seeds = goalSeeds(Point.of(tile % CollisionMap.WIDTH, tile / CollisionMap.WIDTH), gc, danger, times);
            if (seeds == null) {
                continue;
            }
            for (int m = 0; m < seeds.length; m++) {
                if (seeds[m] != Integer.MAX_VALUE) {
                    long p = CchMetric.pack(seeds[m], times[m]);
                    if (p < cost[m]) {
                        cost[m] = p;
                        owner[m] = g;
                    }
                }
            }
        }
    }

    /**
     * Where {@code forward} meets {@code g} cheapest, as an index into its ranks (the lowest rank of equal
     * minima), or -1.
     */
    private int meetIndex(CchSearch.Half forward, GoalLabels g) {
        searches.incrementAndGet();
        long best = CchMetric.INF;
        int at = -1;
        int[] ranks = g.ranks();
        long[] costs = g.costs();
        for (int i = 0; i < ranks.length; i++) {
            int r = ranks[i];
            if (forward.reached(r)) {
                long via = forward.dist[r] + costs[i];
                if (via < best) {
                    best = via;
                    at = i;
                }
            }
        }
        return at;
    }

    /** The candidate {@code g}'s entry {@code i} descends from: down its preds to the seed, whose owner was kept. */
    private int ownerAt(GoalLabels g, int i) {
        int[] ranks = g.ranks();
        int[] preds = g.preds();
        while (preds[i] >= 0) {
            i = Arrays.binarySearch(ranks, topology.tail[preds[i]]);
        }
        return g.owners()[i];
    }

    /** The candidate {@link #priceFunded} reached over {@code gB}, or -1. */
    private int fundedOwner(FundedFrom f, GoalLabels gB) {
        long[] backs = backsTo(gB, f.klassB);
        int bank = -1;
        long best = Long.MAX_VALUE;
        long bestTime = Long.MAX_VALUE;
        for (int i = 0; i < bankNodes.length; i++) {
            long to = f.toBank[i];
            long back = backs[i];
            if (to == CchMetric.INF || back == CchMetric.INF) {
                continue;
            }
            long total = (long) CchMetric.weightOf(to) + f.stops[i] + CchMetric.weightOf(back);
            long time = (to & 0xFFFFFFFFL) + f.stops[i] + (back & 0xFFFFFFFFL);
            if (total < best || (total == best && time < bestTime)) {
                best = total;
                bestTime = time;
                bank = i;
            }
        }
        if (bank < 0) {
            return -1;
        }
        long[] labels = f.klassB.metric(this).fromBankLabels();
        int banks = bankNodes.length;
        int[] slotOf = topology.bankSlot;
        int[] ranks = gB.ranks();
        long[] costs = gB.costs();
        long min = CchMetric.INF;
        int at = -1;
        for (int j = 0; j < ranks.length; j++) {
            int slot = slotOf[ranks[j]];
            if (slot < 0) {
                continue;
            }
            long via = labels[slot * banks + bank] + costs[j];
            if (via < min) {
                min = via;
                at = j;
            }
        }
        return at < 0 ? -1 : ownerAt(gB, at);
    }

    private void keepRow(int slot, BatchKey key, List<? extends Dest> dests, int[] answers) {
        batchRows.set(
                slot,
                new BatchRow(
                        new BatchKey(
                                key.from(),
                                key.inventory(),
                                key.bank(),
                                key.quests(),
                                key.levelRungs(),
                                key.cacheValues(),
                                key.policy(),
                                List.copyOf(dests),
                                key.hash()),
                        answers));
    }

    /**
     * Everything an estimate batch reads of its inputs, read off the account without resolving it: the
     * start tile, the pack, bank and quests by identity (frozen, so identity is content), the levels as
     * {@link #levelRungs}, and the cache values, policy and destinations by value.
     */
    private record BatchKey(
            long from,
            Inventory inventory,
            Bank bank,
            Quests quests,
            int[] levelRungs,
            CacheValues cacheValues,
            NavPolicy policy,
            List<? extends Dest> dests,
            int hash) {
        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o == this
                    || (o instanceof BatchKey k
                            && hash == k.hash
                            && from == k.from
                            && inventory == k.inventory
                            && bank == k.bank
                            && quests == k.quests
                            && Arrays.equals(levelRungs, k.levelRungs)
                            && cacheValues.equals(k.cacheValues)
                            && policy.equals(k.policy)
                            && dests.equals(k.dests));
        }
    }

    private record BatchRow(BatchKey key, int[] answers) {}

    /**
     * What a batch is a function of once its account is resolved: the start tile, the carried class,
     * the banked class where a stop opens a gate and whether it closes none, the policy and the
     * destinations.
     */
    private record ClassBatchKey(
            long from,
            MetricKey carried,
            MetricKey banked,
            boolean superset,
            NavPolicy policy,
            List<? extends Dest> dests,
            int hash) {
        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            return o == this
                    || (o instanceof ClassBatchKey k
                            && hash == k.hash
                            && from == k.from
                            && superset == k.superset
                            && carried.equals(k.carried)
                            && (banked == null ? k.banked == null : banked.equals(k.banked))
                            && policy.equals(k.policy)
                            && dests.equals(k.dests));
        }
    }

    private record ClassRow(ClassBatchKey key, int[] answers) {}

    private final AtomicReferenceArray<ClassRow> classRows = new AtomicReferenceArray<>(BATCH_ROWS);
    private final AtomicLong classRowHits = new AtomicLong();

    private ClassBatchKey classBatchKey(Point from, List<? extends Dest> dests, NavPolicy policy, Resolved r) {
        MetricKey banked = null;
        boolean superset = false;
        if (bankNodes.length > 0 && !policy.assumeAllEligible()) {
            ensureBanked(r, policy);
            if (r.relaxed) {
                banked = r.klassB.key;
                superset = r.bankedSuperset;
            }
        }
        long tile = (long) from.y() * CollisionMap.WIDTH + from.x();
        int hash = Long.hashCode(tile);
        hash = hash * 31 + r.klass.key.hashCode();
        hash = hash * 31 + (banked == null ? 0 : banked.hashCode());
        hash = hash * 31 + (superset ? 1 : 0);
        hash = hash * 31 + policy.hashCode();
        hash = hash * 31 + dests.hashCode();
        return new ClassBatchKey(tile, r.klass.key, banked, superset, policy, dests, hash);
    }

    /**
     * What the levels decide, as rungs: the danger rung plus one (0 when the account is charged no danger,
     * which the levels decide as well as the policy), base Hits
     * on the current-Hits gates' rungs, since food aboard lets such a gate read the base level
     * ({@link Fact#foodLifts()}), then {@link SkillBreakpoints#rungs}. Equal rungs answer every fact
     * alike and choose the same danger field.
     */
    private int[] levelRungs(Stats stats, NavPolicy policy) {
        int[] rungs = skillBreakpoints.rungs(stats, 2);
        rungs[0] = policy.chargesDanger(stats) ? dangerMap.rungOf(stats.combatLevel()) + 1 : 0;
        rungs[1] = skillBreakpoints.currentRung(Skill.HITS, stats.baseLevel(Skill.HITS));
        return rungs;
    }

    /**
     * The key a batch is kept under, or null where it is not kept: an engine that keeps nothing, one
     * destination, more than {@link #BATCH_ROW_MAX_DESTS}, a destination that is no value (a
     * {@code Targetable} moves), or a pack or bank that is not frozen.
     */
    private BatchKey batchKey(AccountFacts account, Point from, List<? extends Dest> dests, NavPolicy policy) {
        if (batchRows == null || dests.size() < 2 || dests.size() > BATCH_ROW_MAX_DESTS) {
            return null;
        }
        for (Dest d : dests) {
            if (!(d instanceof Area || d instanceof Dest.AnyOf)) {
                return null;
            }
        }
        Inventory inventory = account.inventory();
        Bank bank = account.bank();
        if (!(inventory instanceof Inventory.Frozen) || !(bank instanceof Bank.Frozen)) {
            return null;
        }
        Quests quests = account.quests();
        int[] levelRungs = levelRungs(account.stats(), policy);
        CacheValues cacheValues = account.cacheValues();
        long tile = (long) from.y() * CollisionMap.WIDTH + from.x();
        int hash = Long.hashCode(tile);
        hash = hash * 31 + System.identityHashCode(inventory);
        hash = hash * 31 + System.identityHashCode(bank);
        hash = hash * 31 + System.identityHashCode(quests);
        hash = hash * 31 + Arrays.hashCode(levelRungs);
        hash = hash * 31 + cacheValues.hashCode();
        hash = hash * 31 + policy.hashCode();
        hash = hash * 31 + dests.hashCode();
        return new BatchKey(tile, inventory, bank, quests, levelRungs, cacheValues, policy, dests, hash);
    }

    long batchRowHits() {
        return batchRowHits.get();
    }

    long targetLabelBuilds() {
        return targetLabelBuilds.get();
    }

    /** Every destination's price as an estimate batch makes it, packed {@code weight << 32 | time}; -1 for no route. */
    long[] weighBatch(AccountFacts account, Point from, List<? extends Dest> dests, NavPolicy policy) {
        long[] out = new long[dests.size()];
        Arrays.fill(out, -1L);
        if (!map.inBounds(from.x(), from.y())) {
            return out;
        }
        Resolved r = resolve(account, policy);
        CchSearch fromSide = batchTl.get();
        runFromTile(fromSide.forward, from, r.klass);
        FundedFrom funded = fundedFrom(from, policy, r, fromSide);
        for (int i = 0; i < out.length; i++) {
            Price p = priceDest(from, dests.get(i), policy, r, funded, fromSide);
            if (p.deci() >= 0) {
                out[i] = p.weight() << 32 | p.time();
            }
        }
        return out;
    }

    long classRowHits() {
        return classRowHits.get();
    }

    /**
     * One destination priced: an {@code AnyOf} by its lightest option, as a plan chooses; an area at its
     * {@link #entryTile}.
     */
    private Price priceDest(
            Point from, Dest dest, NavPolicy policy, Resolved r, FundedFrom funded, CchSearch fromSide) {
        if (dest instanceof Dest.AnyOf any) {
            Price best = Price.NONE;
            for (Area a : any.options()) {
                Price p = priceDest(from, a, policy, r, funded, fromSide);
                if (p.deci() >= 0 && p.beats(best)) {
                    best = p;
                }
            }
            return best;
        }
        Point goal = dest instanceof Area a ? entryTile(from, a, r.klass, r.danger, fromSide) : primaryTile(dest);
        if (goal == null || !map.inBounds(goal.x(), goal.y())) {
            return Price.NONE;
        }
        return priceTile(from, goal, r, funded, fromSide);
    }

    @SuppressWarnings("ThreadLocalUsage")
    private final ThreadLocal<LocalField> localFieldTl = new ThreadLocal<>();

    private final ThreadLocal<FlatWalk> flatTl = ThreadLocal.withInitial(FlatWalk::new);
    private final StepMasks stepMasks;

    /** The exact local walks around {@code from}, when {@code goal} is within a window's reach; null beyond. */
    private @Nullable LocalField localField(Point from, Point goal, DangerField danger) {
        int radius = LocalField.radiusFor(from, goal);
        if (radius < 0) {
            return null;
        }
        LocalField f = localFieldTl.get();
        if (f == null || !f.serves(from, danger, radius)) {
            f = LocalField.build(stepMasks, map, danger, from, radius);
            localFieldTl.set(f);
        }
        return f;
    }

    /**
     * The time of the hierarchy's route {@code chain} when it is a different route from the exact local
     * walk of weight {@code localWeight}, else -1. A walk-only route is priced through entrance spans,
     * a tile or two short where it hugs a cluster corner, so reading lighter is not enough: it may be
     * the local walk itself. Told apart by walking the window {@link WindowSearch#window} gives its seams.
     */
    private long differentRouteTime(
            Point from, Point goal, List<int[]> chain, long chainTime, long localWeight, DangerField danger) {
        if (chain == null) {
            return -1;
        }
        // A route that takes a transport is a different route by construction.
        if (!hopsOf(chain).isEmpty()) {
            return chainTime;
        }
        int x0 = goal.x();
        int y0 = goal.y();
        int x1 = x0;
        int y1 = y0;
        for (int i = 1; i < chain.size(); i++) {
            if (chain.get(i)[1] == TAG_BOUNDARY) {
                Rect s = spanOf(chain.get(i)[0]);
                x0 = Math.min(x0, s.minX());
                y0 = Math.min(y0, s.minY());
                x1 = Math.max(x1, s.maxX());
                y1 = Math.max(y1, s.maxY());
            }
        }
        Rect box = WindowSearch.window(map, from, Rect.of(goal, goal), new Rect(x0, y0, x1, y1));
        if (box == null) {
            return -1;
        }
        // A box charging no tile prices as the plain walk, which a goal-directed search finds without filling it.
        if (danger == plainDanger() || !danger.chargesAnyIn(box.minX(), box.minY(), box.maxX(), box.maxY())) {
            int steps = flatTl.get()
                    .steps(
                            stepMasks,
                            box.minX(),
                            box.minY(),
                            box.maxX(),
                            box.maxY(),
                            from.x(),
                            from.y(),
                            goal.x(),
                            goal.y());
            return steps >= 0 && steps * ((long) STEP_COST) < localWeight ? steps * STEP_COST : -1;
        }
        LocalField walk = LocalField.over(stepMasks, danger, from, box);
        return walk.weight(goal.x(), goal.y()) < localWeight ? walk.time(goal.x(), goal.y()) : -1;
    }

    /**
     * The plain walk's exact time inside the local window around {@code from}, or
     * {@code Long.MAX_VALUE} beyond the window or with no walk in it.
     */
    private long plainLocalTime(Point from, Point goal) {
        int radius = LocalField.radiusFor(from, goal);
        if (radius < 0) {
            return Long.MAX_VALUE;
        }
        LocalField own = localFieldTl.get();
        if (own != null && own.servesPlain(from, radius)) {
            int w = own.weight(goal.x(), goal.y());
            return w == Integer.MAX_VALUE ? Long.MAX_VALUE : w;
        }
        int steps = flatTl.get()
                .steps(
                        stepMasks,
                        Math.max(0, from.x() - radius),
                        Math.max(0, from.y() - radius),
                        Math.min(map.width() - 1, from.x() + radius),
                        Math.min(map.height() - 1, from.y() + radius),
                        from.x(),
                        from.y(),
                        goal.x(),
                        goal.y());
        return steps < 0 ? Long.MAX_VALUE : (long) steps * STEP_COST;
    }

    /**
     * Tile to tile: the lightest of the local window's exact walk, the hierarchy's route, and the funded
     * route, which stops at a bank for the gate item it lacks. Ranked by weight; the winner's own time
     * is reported.
     */
    private Price priceTile(Point from, Point goal, Resolved r, FundedFrom funded, CchSearch fromSide) {
        long best = Long.MAX_VALUE;
        long bestTime = Long.MAX_VALUE;
        LocalField local = localField(from, goal, r.danger);
        boolean exact = local != null && local.weight(goal.x(), goal.y()) != Integer.MAX_VALUE;
        if (exact) {
            best = local.weight(goal.x(), goal.y());
            bestTime = local.time(goal.x(), goal.y());
        }
        long bestLocal = best;
        boolean viaHierarchy = false;
        GoalLabels g = goalLabels(goal, r.klass);
        if (g.ranks().length > 0) {
            long p = meetLabels(fromSide.forward, g);
            if (p != CchMetric.INF) {
                long cost = CchMetric.weightOf(p);
                long time = p & 0xFFFFFFFFL;
                // Against an exact local walk, the hierarchy's time counts only when its route is a different one;
                // its lighter weight still ranks, since the funded arm below is priced through the hierarchy too.
                boolean lighter = cost < best || (cost == best && time < bestTime);
                if (lighter) {
                    best = cost;
                    if (!exact) {
                        bestTime = time;
                        viaHierarchy = true;
                    } else {
                        long different = differentRouteTime(
                                from,
                                goal,
                                chain(toGoal(fromSide, from, goal, r.klass, g), fromSide),
                                time,
                                bestLocal,
                                r.danger);
                        if (different >= 0) {
                            bestTime = different;
                            viaHierarchy = true;
                        }
                    }
                }
            }
        }
        Price direct = new Price(best, bestTime);
        if (direct.deci() < 0) {
            // Nothing direct, but a bank stop may hold the key.
            if (!funded.usable) {
                return Price.NONE;
            }
            GoalLabels gB = goalLabels(goal, funded.klassB);
            if (bankedDirectFloor(from, funded, gB) == Long.MAX_VALUE) {
                bankedFloorSkips.incrementAndGet();
                return Price.NONE;
            }
            return priceFunded(funded, gB);
        }
        // No funded weight is under the cheapest stop, nor under the all-usable floor, nor under the goal
        // priced direct under the banked class plus a stop: each rules the funded shape out more cheaply.
        if (funded.usable && funded.cheapestStop <= best) {
            if (!funded.klassB.metricAtHand(this) && fundedFloor(goal, r, funded) > best) {
                fundedFloorSkips.incrementAndGet();
                return walkedDirect(from, goal, direct, viaHierarchy);
            }
            GoalLabels gB = goalLabels(goal, funded.klassB);
            if (bankedDirectFloor(from, funded, gB) > best) {
                bankedFloorSkips.incrementAndGet();
                return walkedDirect(from, goal, direct, viaHierarchy);
            }
            Price viaBank = priceFunded(funded, gB);
            if (viaBank.deci() >= 0 && viaBank.beats(direct)) {
                return viaBank;
            }
        }
        return walkedDirect(from, goal, direct, viaHierarchy);
    }

    /**
     * {@code direct}'s time as walked when it is the local window's walk: the plain walk's. A route the
     * hierarchy chose keeps its own time.
     */
    private Price walkedDirect(Point from, Point goal, Price direct, boolean viaHierarchy) {
        if (direct.deci() < 0 || viaHierarchy) {
            return direct;
        }
        long walked = plainLocalTime(from, goal);
        return walked == Long.MAX_VALUE ? direct : new Price(direct.weight(), walked);
    }

    /**
     * Each node's walk to {@code goal}, filling {@code outTimes}; null for a fully blocked goal, which no
     * walk ends on.
     */
    private int[] goalSeeds(Point goal, int gc, DangerField danger, int[] outTimes) {
        return map.fullyBlocked(goal.x(), goal.y())
                ? null
                : legs.costsAt(gc, goal.x(), goal.y(), false, danger, outTimes);
    }

    /**
     * The one tile {@code dest} is seeded from where nothing finer applies: an area's centre when
     * standable, else its standable tile nearest the centre; an {@code AnyOf}'s first option's; else null.
     */
    private Point primaryTile(Dest dest) {
        if (dest instanceof Area a) {
            int cx = (a.minX() + a.maxX()) / 2;
            int cy = (a.minY() + a.maxY()) / 2;
            if (!map.fullyBlocked(cx, cy)) {
                return Point.of(cx, cy);
            }
            Point best = null;
            int bestD = Integer.MAX_VALUE;
            for (int y = a.minY(); y <= a.maxY(); y++) {
                for (int x = a.minX(); x <= a.maxX(); x++) {
                    if (map.inBounds(x, y) && !map.fullyBlocked(x, y)) {
                        int d = Math.max(Math.abs(x - cx), Math.abs(y - cy));
                        if (d < bestD) {
                            bestD = d;
                            best = Point.of(x, y);
                        }
                    }
                }
            }
            return best;
        }
        if (dest instanceof Dest.AnyOf any && !any.options().isEmpty()) {
            return primaryTile(any.options().get(0));
        }
        return null;
    }

    /**
     * The tile of {@code a} cheapest to reach from {@code from} under {@code k}: {@code from} itself when
     * inside, else the lightest of its {@link #entryTiles}, by the local window's walk where it reaches
     * and the hierarchy met against the area's labels, else {@link #primaryTile}. One tile for plans,
     * estimates and walks alike.
     */
    private @Nullable Point entryTile(Point from, Area a, Klass k, DangerField danger, CchSearch fromSide) {
        if (a.contains(from.x(), from.y())) {
            return from;
        }
        int[] tiles = entryTiles(a);
        if (tiles.length == 0) {
            return primaryTile(a);
        }
        if (tiles.length == 1) {
            return tileOf(tiles[0]);
        }
        long bestW = Long.MAX_VALUE;
        long bestT = Long.MAX_VALUE;
        int best = -1;
        Point far = null;
        int reach = -1;
        for (int t : tiles) {
            Point p = tileOf(t);
            if (LocalField.radiusFor(from, p) >= 0) {
                int d = Point.chebyshev(from.x(), from.y(), p.x(), p.y());
                if (d > reach) {
                    reach = d;
                    far = p;
                }
            }
        }
        LocalField local = far == null ? null : localField(from, far, danger);
        if (local != null) {
            for (int t : tiles) {
                Point p = tileOf(t);
                if (LocalField.radiusFor(from, p) < 0) {
                    continue;
                }
                int w = local.weight(p.x(), p.y());
                if (w != Integer.MAX_VALUE) {
                    long time = local.time(p.x(), p.y());
                    if (w < bestW || (w == bestW && time < bestT)) {
                        bestW = w;
                        bestT = time;
                        best = t;
                    }
                }
            }
        }
        int via = hierarchyEntry(from, a, k, fromSide, bestW, bestT);
        return via >= 0 ? tileOf(via) : best >= 0 ? tileOf(best) : primaryTile(a);
    }

    private int hierarchyEntry(Point from, Area a, Klass k, CchSearch fromSide, long bestW, long bestT) {
        long[] best = hierarchyBest(from, a, k, fromSide);
        return best != null && (best[0] < bestW || (best[0] == bestW && best[1] < bestT)) ? (int) best[2] : -1;
    }

    /** The hierarchy's lightest way into {@code a} as {@code {weight, time, tile}}; null when it reaches none. */
    private long @Nullable [] hierarchyBest(Point from, Area a, Klass k, CchSearch fromSide) {
        GoalLabels g = areaLabels(a, k);
        if (g.ranks().length == 0) {
            return null;
        }
        runFromTile(fromSide.forward, from, k);
        int at = meetIndex(fromSide.forward, g);
        if (at < 0) {
            return null;
        }
        long p = fromSide.forward.dist[g.ranks()[at]] + g.costs()[at];
        return new long[] {CchMetric.weightOf(p), p & 0xFFFFFFFFL, ownerAt(g, at)};
    }

    /**
     * The walk into {@code a} under {@code k}, a walk-only class, charged or not. Its local part is the
     * plain walk to the area's nearest tile inside {@link WindowSearch#window}. Charged, it is taken where
     * the window charges nothing or its charged walk is no heavier than the hierarchy's; else the leg
     * names the hierarchy's seams, since the way round lies outside the window.
     */
    private @Nullable Leg walkInto(Point from, Area a, Klass k, CchSearch s, boolean charged) {
        if (a.contains(from.x(), from.y())) {
            return new Leg(List.of(), 0);
        }
        long local = Long.MAX_VALUE;
        Point localTile = null;
        Rect win = WindowSearch.window(map, from, new Rect(a.minX(), a.minY(), a.maxX(), a.maxY()), null);
        if (charged
                && win != null
                && k.danger != null
                && k.danger.intersects(win.minX(), win.minY(), win.maxX(), win.maxY())) {
            Leg sealed = sealedWindow(from, a, k, s, win);
            if (sealed != null) {
                return sealed;
            }
        }
        if (win != null) {
            FlatWalk flat = flatTl.get();
            int steps = flat.stepsInto(
                    stepMasks,
                    win.minX(),
                    win.minY(),
                    win.maxX(),
                    win.maxY(),
                    from.x(),
                    from.y(),
                    a.minX(),
                    a.minY(),
                    a.maxX(),
                    a.maxY());
            if (steps >= 0) {
                local = (long) steps * STEP_COST;
                localTile = Point.of(flat.reached % 65536, flat.reached / 65536);
            }
        }
        if (charged && localTile != null) {
            return new Leg(List.of(), (int) local);
        }
        int via = hierarchyEntry(from, a, k, s, local, local);
        if (via >= 0) {
            return charged ? chargedWalk(from, tileOf(via), k, s) : plainWalk(from, tileOf(via), s);
        }
        if (localTile != null) {
            return new Leg(List.of(), (int) local);
        }
        Point goal = primaryTile(a);
        if (goal == null || !map.inBounds(goal.x(), goal.y())) {
            return null;
        }
        return charged ? chargedWalk(from, goal, k, s) : plainWalk(from, goal, s);
    }

    /** How far the hierarchy's weight to an area may read under the window's own walk and still be that walk. */
    private static final long SPAN_SLACK = 2L * STEP_COST;

    /**
     * The charged walk into {@code a} for a window the field reaches into: the window's own where it is
     * no heavier than the hierarchy's, else the hierarchy's, seams and all. Null when neither reaches.
     */
    private @Nullable Leg sealedWindow(Point from, Area a, Klass k, CchSearch s, Rect win) {
        long[] best = hierarchyBest(from, a, k, s);
        FlatWalk flat = flatTl.get();
        long window = flat.weightInto(
                stepMasks,
                k.danger,
                win.minX(),
                win.minY(),
                win.maxX(),
                win.maxY(),
                from.x(),
                from.y(),
                a.minX(),
                a.minY(),
                a.maxX(),
                a.maxY(),
                best == null ? Long.MAX_VALUE : best[0] + SPAN_SLACK);
        if (window >= 0) {
            return new Leg(List.of(), flat.reachedSteps * STEP_COST);
        }
        return best == null ? null : hierarchyWalk(from, tileOf((int) best[2]), k, s);
    }

    private static Point tileOf(int tile) {
        return Point.of(tile % CollisionMap.WIDTH, tile / CollisionMap.WIDTH);
    }

    /**
     * The tiles a route can first stand on in {@code a}, as {@code y * WIDTH + x}: its walkable edge
     * tiles, which a walk in crosses, and its walkable graph nodes, where a transport lands.
     */
    private int[] entryTiles(Area a) {
        IntArrayList out = new IntArrayList();
        for (int y = a.minY(); y <= a.maxY(); y++) {
            boolean edgeRow = y == a.minY() || y == a.maxY();
            for (int x = a.minX(); x <= a.maxX(); x++) {
                if (!(edgeRow || x == a.minX() || x == a.maxX())) {
                    x = a.maxX() - 1;
                    continue;
                }
                if (map.componentId(x, y) != 0) {
                    out.add(y * CollisionMap.WIDTH + x);
                }
            }
        }
        int step = ClusterGeometry.CLUSTER_SIZE;
        IntOpenHashSet seen = new IntOpenHashSet();
        for (int y = a.minY(); y <= a.maxY() + step - 1; y += step) {
            for (int x = a.minX(); x <= a.maxX() + step - 1; x += step) {
                int gc = ClusterGeometry.clusterOf(Math.min(x, a.maxX()), Math.min(y, a.maxY()));
                if (!seen.add(gc)) {
                    continue;
                }
                int first = graph.clusterFirstNode(gc);
                for (int n = first; n < first + graph.clusterNodeCount(gc); n++) {
                    int nx = graph.nodeX(n), ny = graph.nodeY(n);
                    boolean edge = nx == a.minX() || nx == a.maxX() || ny == a.minY() || ny == a.maxY();
                    if (!edge && a.contains(nx, ny) && map.componentId(nx, ny) != 0) {
                        out.add(ny * CollisionMap.WIDTH + nx);
                    }
                }
            }
        }
        return out.toIntArray();
    }

    private record AreaKey(int minX, int minY, int maxX, int maxY, MetricKey metric) implements LabelKey {
        @Override
        public int hashCode() {
            return (((minX * 31 + minY) * 31 + maxX) * 31 + maxY) * 31 + metric.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            return o == this
                    || (o instanceof AreaKey k
                            && minX == k.minX
                            && minY == k.minY
                            && maxX == k.maxX
                            && maxY == k.maxY
                            && metric.equals(k.metric));
        }
    }

    /** {@code a}'s labels under {@code k}: one backward half seeded from every entry tile, owners the tiles. */
    private GoalLabels areaLabels(Area a, Klass k) {
        AreaKey key = new AreaKey(a.minX(), a.minY(), a.maxX(), a.maxY(), k.key);
        Kept held = goalLabels.getIfPresent(key);
        return (GoalLabels) (held != null ? held : goalLabels.get(key, _ -> computeAreaLabels(entryTiles(a), k)));
    }

    private GoalLabels computeAreaLabels(int[] tiles, Klass k) {
        searches.incrementAndGet();
        CchSearch.Half half = searchTl.get().backward;
        half.reset();
        int[] owner = ownerTl.get();
        boolean seeded = false;
        it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap<IntArrayList> byCluster =
                new it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap<>();
        for (int t : tiles) {
            byCluster
                    .computeIfAbsent(
                            ClusterGeometry.clusterOf(t % CollisionMap.WIDTH, t / CollisionMap.WIDTH),
                            _ -> new IntArrayList())
                    .add(t);
        }
        for (var e : byCluster.int2ObjectEntrySet()) {
            int gc = e.getIntKey();
            int nodes = graph.clusterNodeCount(gc);
            long[] cost = new long[nodes];
            int[] from = new int[nodes];
            Arrays.fill(cost, CchMetric.INF);
            int[] times = new int[nodes];
            for (int t : e.getValue()) {
                int[] seeds = goalSeeds(tileOf(t), gc, k.danger, times);
                if (seeds == null) {
                    continue;
                }
                for (int m = 0; m < seeds.length; m++) {
                    if (seeds[m] != Integer.MAX_VALUE) {
                        long p = CchMetric.pack(seeds[m], times[m]);
                        if (p < cost[m]) {
                            cost[m] = p;
                            from[m] = t;
                        }
                    }
                }
            }
            int first = graph.clusterFirstNode(gc);
            for (int m = 0; m < nodes; m++) {
                if (cost[m] != CchMetric.INF && half.seed(first + m, cost[m])) {
                    owner[topology.rankOf[first + m]] = from[m];
                    seeded = true;
                }
            }
        }
        if (!seeded) {
            return GoalLabels.NONE;
        }
        half.run(k.metric(this));
        int n = half.size();
        int[] ranks = new int[n];
        long[] costs = new long[n];
        int[] preds = new int[n];
        int[] owners = new int[n];
        int kept = 0;
        for (int i = 0; i < n; i++) {
            int r = half.rankAt(i);
            long d = half.dist[r];
            if (d != CchMetric.INF) {
                ranks[kept] = r;
                preds[kept] = half.pred[r];
                owners[kept] = half.pred[r] < 0 ? owner[r] : -1;
                costs[kept++] = d;
            }
        }
        return new GoalLabels(
                Arrays.copyOf(ranks, kept),
                Arrays.copyOf(costs, kept),
                Arrays.copyOf(preds, kept),
                bankNodes.length,
                new long[1][],
                Arrays.copyOf(owners, kept));
    }

    private volatile Map<String, Prereq> gatesByName;

    private volatile Map<String, List<String>> sameGateByName;

    @Override
    public List<String> sameGate(String transport) {
        Map<String, List<String>> byName = sameGateByName;
        if (byName == null) {
            List<List<String>> byGate = new ArrayList<>(uniquePrereqs.length);
            for (int p = 0; p < uniquePrereqs.length; p++) {
                byGate.add(new ArrayList<>());
            }
            for (int i = 0; i < names.length; i++) {
                List<String> group = byGate.get(prereqOf[i]);
                if (!group.contains(names[i])) {
                    group.add(names[i]);
                }
            }
            byName = new HashMap<>(names.length);
            for (int i = 0; i < names.length; i++) {
                byName.putIfAbsent(names[i], List.copyOf(byGate.get(prereqOf[i])));
            }
            sameGateByName = byName;
        }
        return byName.getOrDefault(transport, List.of(transport));
    }

    @Override
    public @Nullable Prereq gateOf(String transport) {
        Map<String, Prereq> byName = gatesByName;
        if (byName == null) {
            byName = new HashMap<>(transports.size());
            for (int i = 0; i < transports.size(); i++) {
                byName.putIfAbsent(names[i], transports.get(i).prereq());
            }
            gatesByName = byName;
        }
        return byName.get(transport);
    }

    @Override
    public PlanResult plan(PlanRequest request) {
        // Kept per thread by its inputs; a result is immutable, so a repeat gets the very object.
        PlanKey key = planKey(request);
        PlanMemo memo = null;
        if (key != null) {
            memo = planTl.get();
            for (int i = 0; i < memo.keys.length; i++) {
                PlanKey k = memo.keys[i];
                if (k != null && k.matches(key)) {
                    planHits.incrementAndGet();
                    return memo.results[i];
                }
            }
        }
        PlanResult result = resultOf(planRanked(request));
        if (CHECKED && result instanceof PlanResult.Planned(Route route)) {
            String broken = routeViolation(request, route);
            if (broken != null) {
                throw new IllegalStateException("planned a route that cannot be walked: " + broken + " — " + route);
            }
        }
        if (key != null) {
            memo.keys[memo.next] = key;
            memo.results[memo.next] = result;
            memo.next = (memo.next + 1) % memo.keys.length;
        }
        return result;
    }

    /**
     * What decides a plan, read off the request: the start tile, the account's inputs as
     * {@link BatchKey} reads them, the policy, and the destination and loadout by value.
     */
    private record PlanKey(
            long from,
            Inventory inventory,
            Bank bank,
            Quests quests,
            int[] levelRungs,
            CacheValues cacheValues,
            NavPolicy policy,
            Dest dest,
            LoadoutSpec loadout) {

        PlanKey withFrom(long tile) {
            return new PlanKey(tile, inventory, bank, quests, levelRungs, cacheValues, policy, dest, loadout);
        }

        boolean matches(PlanKey k) {
            return from == k.from
                    && inventory == k.inventory
                    && bank == k.bank
                    && quests == k.quests
                    && policy.equals(k.policy)
                    && dest.equals(k.dest)
                    && loadout.equals(k.loadout)
                    && Arrays.equals(levelRungs, k.levelRungs)
                    && cacheValues.equals(k.cacheValues);
        }
    }

    /** The class a walk is priced under: no transport, no danger. */
    private volatile Klass walkKlass;

    private Klass walkKlass() {
        Klass k = walkKlass;
        if (k == null) {
            k = new Klass(this, new boolean[transports.size()], baseCosts, plainDanger());
            walkKlass = k;
        }
        return k;
    }

    private DangerField plainDanger() {
        return dangerFields.computeIfAbsent(0, _ -> new DangerField(dangerMap, 0, 0));
    }

    /** A walk's own searches, apart from a plan's, whose halves hold the account's class. */
    private final ThreadLocal<CchSearch> walkFromTl;

    @Override
    public TracedRoute trace(Point from, Route route) {
        return new Tracer(map, graph).trace(from, route);
    }

    @Override
    public @Nullable Leg walk(Point from, Area to) {
        if (!map.inBounds(from.x(), from.y())) {
            return null;
        }
        return walkInto(from, to, walkKlass(), walkFromTl.get(), false);
    }

    private final ConcurrentHashMap<Integer, Klass> dangerWalkKlasses = new ConcurrentHashMap<>();

    @Override
    public @Nullable Leg walk(Point from, Area to, AccountFacts account, NavPolicy policy) {
        if (!policy.chargesDanger(account.stats())) {
            return walk(from, to);
        }
        if (!map.inBounds(from.x(), from.y())) {
            return null;
        }
        DangerField field = resolve(account, policy).danger;
        Klass walkK = dangerWalkKlasses.computeIfAbsent(
                field.signature(), sig -> new Klass(this, new boolean[transports.size()], baseCosts, field));
        return walkInto(from, to, walkK, walkFromTl.get(), true);
    }

    /**
     * The walk to {@code goal} under a charged walk class: the local window's plain walk where it gets
     * there, else the hierarchy's.
     */
    private @Nullable Leg chargedWalk(Point from, Point goal, Klass k, CchSearch s) {
        if (from.equals(goal)) {
            return new Leg(List.of(), 0);
        }
        long localTime = plainLocalTime(from, goal);
        if (localTime != Long.MAX_VALUE) {
            return new Leg(List.of(), (int) localTime);
        }
        return hierarchyWalk(from, goal, k, s);
    }

    private @Nullable Leg hierarchyWalk(Point from, Point goal, Klass k, CchSearch s) {
        GoalLabels g = goalLabels(goal, k);
        if (g.ranks().length > 0) {
            runFromTile(s.forward, from, k);
        }
        Comp comp = toGoal(s, from, goal, k, g);
        if (comp.cost == Long.MAX_VALUE) {
            return null;
        }
        List<int[]> chain = chain(comp, s);
        List<Rect> spans = new ArrayList<>();
        for (int i = 1; i < chain.size(); i++) {
            if (chain.get(i)[1] == TAG_BOUNDARY) {
                spans.add(spanOf(chain.get(i)[0]));
            }
        }
        return new Leg(spans, (int) Math.min(Integer.MAX_VALUE, comp.time));
    }

    /** The uncharged walk to {@code goal}: the local window's unless the hierarchy's is a different, lighter one. */
    private @Nullable Leg plainWalk(Point from, Point goal, CchSearch s) {
        if (from.equals(goal)) {
            return new Leg(List.of(), 0);
        }
        Klass k = walkKlass();
        long localTime = plainLocalTime(from, goal);
        GoalLabels g = goalLabels(goal, k);
        Comp comp;
        if (g.ranks().length > 0) {
            runFromTile(s.forward, from, k);
            comp = toGoal(s, from, goal, k, g);
        } else {
            comp = toGoal(s, from, goal, k, g);
        }
        if (comp.cost < localTime) {
            List<int[]> chain = chain(comp, s);
            long time = localTime == Long.MAX_VALUE
                    ? comp.time
                    : differentRouteTime(from, goal, chain, comp.time, localTime, k.danger);
            if (time >= 0) {
                List<Rect> spans = new ArrayList<>();
                for (int i = 1; i < chain.size(); i++) {
                    if (chain.get(i)[1] == TAG_BOUNDARY) {
                        spans.add(spanOf(chain.get(i)[0]));
                    }
                }
                return new Leg(spans, (int) time);
            }
        }
        return localTime == Long.MAX_VALUE ? null : new Leg(List.of(), (int) localTime);
    }

    private static final class PlanMemo {
        final PlanKey[] keys = new PlanKey[4];
        final PlanResult[] results = new PlanResult[4];
        int next;
    }

    private final ThreadLocal<PlanMemo> planTl = ThreadLocal.withInitial(PlanMemo::new);
    private final AtomicLong planHits = new AtomicLong();

    /** The key a plan is kept under, or null: an engine that keeps nothing, or no {@link #requestKey}. */
    private PlanKey planKey(PlanRequest request) {
        return batchRows == null ? null : requestKey(request);
    }

    /**
     * {@code request} as values, or null: a start off the map, a destination that is no value, or an
     * unfrozen pack or bank.
     */
    private PlanKey requestKey(PlanRequest request) {
        Point from = request.from();
        Dest dest = request.dest();
        if (!map.inBounds(from.x(), from.y()) || !(dest instanceof Area || dest instanceof Dest.AnyOf)) {
            return null;
        }
        AccountFacts account = request.account();
        Inventory inventory = account.inventory();
        Bank bank = account.bank();
        if (!(inventory instanceof Inventory.Frozen) || !(bank instanceof Bank.Frozen)) {
            return null;
        }
        return new PlanKey(
                (long) from.y() * CollisionMap.WIDTH + from.x(),
                inventory,
                bank,
                account.quests(),
                levelRungs(account.stats(), request.policy()),
                account.cacheValues(),
                request.policy(),
                dest,
                request.loadout());
    }

    @Override
    public @Nullable Object tripKey(PlanRequest request) {
        PlanKey k = requestKey(request);
        return k == null ? null : new TripKey(k);
    }

    private record TripKey(PlanKey key) {
        @Override
        public boolean equals(Object o) {
            return o instanceof TripKey t && t.key.withFrom(key.from).matches(key);
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(key.inventory) * 31 + key.dest.hashCode();
        }
    }

    long planHits() {
        return planHits.get();
    }

    /** A plan's chosen shape with the weight it was chosen by; the route is built only for the winner. */
    private record Ranked(Chosen chosen, long weight, long time, boolean fundedCapped) {
        Ranked(Chosen chosen, long weight, long time) {
            this(chosen, weight, time, false);
        }

        static Ranked none() {
            return new Ranked(null, Long.MAX_VALUE, Long.MAX_VALUE);
        }

        static Ranked capped() {
            return new Ranked(null, Long.MAX_VALUE, Long.MAX_VALUE, true);
        }

        boolean planned() {
            return chosen != null;
        }

        boolean beats(Ranked o) {
            return weight < o.weight || (weight == o.weight && time < o.time);
        }
    }

    private record Chosen(PlanRequest request, Candidate candidate, Area goalArea) {}

    private PlanResult resultOf(Ranked ranked) {
        Chosen c = ranked.chosen();
        return c == null
                ? new PlanResult.NoRoute(
                        ranked.fundedCapped()
                                ? "funded re-search capped at " + FUNDED_RESEARCH_BUDGET + " closure sets"
                                : null)
                : new PlanResult.Planned(buildRoute(c.request(), c.candidate(), c.goalArea()));
    }

    private Ranked planRanked(PlanRequest request) {
        Point from = request.from();
        if (!map.inBounds(from.x(), from.y())) {
            return Ranked.none();
        }
        // A min the pack and bank cannot fill is lowered once, here, so every shape plans the same contract.
        request = new PlanRequest(
                request.account(),
                from,
                request.dest(),
                request.loadout()
                        .reachable(
                                request.account().inventory(), request.account().bank(), serverData),
                request.policy());
        if (request.dest() instanceof Dest.AnyOf any) {
            Ranked best = Ranked.none();
            boolean capped = false;
            for (Area a : any.options()) {
                Ranked r = planRanked(new PlanRequest(request.account(), from, a, request.loadout(), request.policy()));
                capped |= r.fundedCapped();
                if (r.planned() && r.beats(best)) {
                    best = r;
                }
            }
            return best.planned() || !capped ? best : Ranked.capped();
        }
        Area goalArea = (Area) request.dest();
        Resolved r = resolve(request.account(), request.policy());
        Point goal = entryTile(from, goalArea, r.klass, r.danger, searchTl.get());
        if (goal == null || !map.inBounds(goal.x(), goal.y())) {
            return Ranked.none();
        }

        RouteContext facts = r.facts;
        DangerField danger = r.danger;
        boolean[] canUse = r.canUse.clone();
        int[] tCosts = r.tCosts;
        LoadoutSpec loadout = request.loadout();
        Inventory pack = request.account().inventory();

        // Shape A, direct: legal when the arrival contract holds on the pack less the chain's fares. Fares
        // that sum past the purse or breach the loadout are repaired by closing consuming hops; a band the
        // pack fails before any spend is the funded shape's to meet.
        Candidate direct = directCandidate(facts, danger, canUse, tCosts, from, goal);
        boolean loadoutRepairable = loadout.satisfiedBy(pack);
        boolean fareStarved = false;
        if (direct != null && fareViolation(direct, facts, pack, loadout, loadoutRepairable) != null) {
            fareStarved = true;
            direct = repairFares(facts, danger, canUse, tCosts, from, goal, direct, pack, loadout, loadoutRepairable);
        }
        boolean directOk = direct != null && loadout.satisfiedBy(projectPack(pack, Map.of(), direct.debits));
        if (direct != null && !directOk) {
            fareStarved = true;
        }

        // Every bank shape starts with a walk to a bank under this class and a stop, so a direct route
        // lighter than the lightest such pair beats them all, whatever the rest would cost.
        boolean stopsCannotWin = directOk
                && direct.cost < cheapestStop(from, klassFor(canUse, tCosts, danger), stopCosts(request.policy()));
        if (stopsCannotWin) {
            planStopSkips.incrementAndGet();
        }
        Candidate viaBank = null;
        Settle settle =
                stopsCannotWin ? null : loadout.settleAt(pack, request.account().bank(), serverData);
        if (settle != null && settle.satisfiable() && !settle.manifest().isEmpty()) {
            // Shape B, via a bank: the leg on priced under the class of the pack the loadout's settle leaves, the
            // stop planned at the booth. It has no fare repair: a leg in the purse cannot pay, or a stop that
            // cannot settle with the fares past it, drops it for the funded shape, which can close hops.
            viaBank = bankViaCandidate(request, r, danger, canUse, tCosts, from, goal, settle);
            if (viaBank != null && viaBank.stopManifest == null) {
                viaBank = null;
                fareStarved = true;
            }
        }

        // Shape C, funded: a withdraw stop grants the credentials and fares the pack lacks.
        boolean[] fundedCapped = new boolean[1];
        Candidate funded = stopsCannotWin
                ? null
                : fundedCandidate(request, r, danger, canUse, tCosts, from, goal, fareStarved, fundedCapped);

        Candidate bestC = null;
        if (directOk) {
            bestC = direct;
        }
        if (viaBank != null && viaBank.beats(bestC)) {
            bestC = viaBank;
        }
        if (funded != null && funded.beats(bestC)) {
            bestC = funded;
        }
        if (bestC != null && bestC.chain.isEmpty() && bestC.bankChainIndex < 0) {
            // The local window's walk is reported as walked, as an estimate reports it.
            long walked = plainLocalTime(from, goal);
            if (walked != Long.MAX_VALUE) {
                bestC.time = walked;
            }
        }
        if (bestC != null) {
            if (bestC.time == Long.MAX_VALUE || bestC.time >= Integer.MAX_VALUE) {
                throw new IllegalStateException("a route shape priced a weight with no duration: " + bestC.cost);
            }
            return new Ranked(new Chosen(request, bestC, goalArea), bestC.cost, bestC.time);
        }
        return fundedCapped[0] ? Ranked.capped() : Ranked.none();
    }

    /**
     * The lightest walk from {@code from} to a bank under {@code k} plus that bank's stop;
     * {@code Long.MAX_VALUE} when none.
     */
    private long cheapestStop(Point from, Klass k, int[] stops) {
        long best = Long.MAX_VALUE;
        Comp[] toBank = tileToBanks(from, k);
        for (int i = 0; i < toBank.length; i++) {
            if (toBank[i].cost != Long.MAX_VALUE) {
                best = Math.min(best, toBank[i].cost + stops[i]);
            }
        }
        return best;
    }

    private final AtomicLong planStopSkips = new AtomicLong();

    /** One priced route shape: its chain, and for a bank shape the stops along it. */
    private static final class Candidate {
        /** The weight shapes are ranked by, danger charge included: never reported. */
        int cost;

        long time = Long.MAX_VALUE;
        /** What the stop at {@link #bankChainIndex} does; null for no stop. */
        Manifest stopManifest;
        /** The deposit shape's second stop, as a chain index; -1 for none. */
        int bankChainIndex2 = -1;

        Manifest stopManifest2;
        List<int[]> chain = new ArrayList<>();

        /** Where in {@link #chain} the stop is; 0 when it is the start tile, -1 for none. */
        int bankChainIndex = -1;

        Map<Integer, Long> debits = Map.of();
        List<Integer> hopOrder = new ArrayList<>();
        Ledger ledger;

        boolean beats(Candidate o) {
            return o == null || cost < o.cost || (cost == o.cost && time < o.time);
        }
    }

    /** {@code c}'s chain read off once it is whole: its hops, its ledger, and the fares of them all. */
    private void seal(Candidate c, AccountFacts account) {
        c.hopOrder = hopsOf(c.chain);
        c.ledger = new Ledger(c.chain, account);
        c.debits = c.ledger.total();
    }

    /** The first chain index past a stop at {@code stop}; -1, for no stop, is the whole chain. */
    private static int pastStop(int stop) {
        return stop <= 0 ? 0 : stop + 1;
    }

    /**
     * The fares a chain spends, by chain index. A stop at index {@code k > 0} opens once the hops through
     * {@code k} have paid; a stop at index 0 is the start tile, ahead of every hop, a cast from it included.
     */
    private final class Ledger {
        private final List<int[]> chain;
        private final List<Map<Integer, Long>> fares;

        Ledger(List<int[]> chain, AccountFacts account) {
            this.chain = chain;
            this.fares = new ArrayList<>(chain.size());
            for (int[] link : chain) {
                fares.add(link[1] < 0 ? Map.of() : fareOf(link[1], account));
            }
        }

        Map<Integer, Long> fareAt(int index) {
            return fares.get(index);
        }

        /** What the hops at chain indices {@code from} up to {@code to} spend. */
        Map<Integer, Long> spent(int from, int to) {
            Map<Integer, Long> out = new LinkedHashMap<>();
            for (int i = from; i < to && i < fares.size(); i++) {
                fares.get(i).forEach((id, n) -> out.merge(id, n, Long::sum));
            }
            return out;
        }

        /** What is spent before a stop at {@code stop} opens. */
        Map<Integer, Long> before(int stop) {
            return spent(0, pastStop(stop));
        }

        /** What is spent after a stop at {@code stop}. */
        Map<Integer, Long> after(int stop) {
            return spent(pastStop(stop), fares.size());
        }

        Map<Integer, Long> total() {
            return spent(0, fares.size());
        }

        /**
         * The first hop at chain indices {@code from} up to {@code to} whose fare, with those before it,
         * {@code purse} cannot pay; -1 for none.
         */
        int firstOverdrawn(int from, int to, Inventory purse) {
            Map<Integer, Long> spent = new HashMap<>();
            for (int i = from; i < to && i < fares.size(); i++) {
                for (Map.Entry<Integer, Long> f : fares.get(i).entrySet()) {
                    if (spent.merge(f.getKey(), f.getValue(), Long::sum) > purse.count(f.getKey())) {
                        return chain.get(i)[1];
                    }
                }
            }
            return -1;
        }

        /** The hops that spend item {@code id}, in chain order. */
        IntArrayList spenders(int id) {
            IntArrayList out = new IntArrayList();
            for (int i = 0; i < fares.size(); i++) {
                if (fares.get(i).containsKey(id)) {
                    out.add(chain.get(i)[1]);
                }
            }
            return out;
        }
    }

    /**
     * A stretch of a chain walked: the first hop whose gate is shut and the first whose fare the pack
     * cannot pay, -1 for none; the free slots the pack walked with must have for every gate on room to
     * open; the pack at the shut gate, null for none; and the pack at its end.
     */
    private record Segment(
            int shut, int overdrawn, int room, @Nullable Inventory atShut, Inventory end) {
        boolean open() {
            return shut < 0 && overdrawn < 0;
        }
    }

    /**
     * The hops of {@code c} at chain indices {@code from} up to {@code to} walked with {@code leaving} as
     * {@link #buildRoute} walks them: each puts on what its gate reads, is tested unless the policy lifts
     * it or bypasses every gate, then pays its fare.
     */
    private Segment walkSegment(Candidate c, int from, int to, Inventory leaving, PlanRequest request) {
        AccountFacts account = request.account();
        RouteContext facts = new RouteContext(account, request.policy(), serverData);
        boolean gated = !request.policy().assumeAllEligible();
        Inventory pack = leaving;
        int shut = -1;
        Inventory atShut = null;
        int overdrawn = -1;
        int room = 0;
        for (int i = from; i < to && i < c.chain.size(); i++) {
            int tag = c.chain.get(i)[1];
            if (tag < 0) {
                continue;
            }
            pack = Packs.wear(pack, transports.get(tag).wornGate(pack, serverData), serverData);
            if (gated && !lifted(request.policy(), tag)) {
                Prereq gate = uniquePrereqs[prereqOf[tag]];
                if (shut < 0 && !gate.test(facts.withAccount(account.withInventory(pack)))) {
                    shut = tag;
                    atShut = pack;
                }
                if (roomFacts.length > 0) {
                    int freed = leaving.usedSlots() - pack.usedSlots();
                    for (Fact f : gate.facts()) {
                        if (f.kind() == Fact.Kind.ROOM_FOR) {
                            room = (int)
                                    Math.max(room, pack.newSlotsFor(serverData, f.itemId(), f.threshold()) - freed);
                        }
                    }
                }
            }
            Map<Integer, Long> fare = c.ledger.fareAt(i);
            for (Map.Entry<Integer, Long> f : fare.entrySet()) {
                if (overdrawn < 0 && pack.count(f.getKey()) < f.getValue()) {
                    overdrawn = tag;
                }
            }
            pack = Packs.consume(pack, fare, serverData);
        }
        return new Segment(shut, overdrawn, room, atShut, pack);
    }

    /** {@link #walkSegment} from past a stop at {@code stop} to the arrival. */
    private Segment walkPast(Candidate c, int stop, Inventory leaving, PlanRequest request) {
        return walkSegment(c, pastStop(stop), c.chain.size(), leaving, request);
    }

    /**
     * The consuming hops of {@code c} one of which must go, or null when its fares are fine: those that
     * spend an item the pack cannot cover the chain's total of, else, where the pack met the loadout
     * before any spend, every consuming hop if the spend breaches it. Fares only add, so every chain
     * that does not fail leaves one of them out, which makes {@link #repairFares} exact.
     */
    private @Nullable IntSet fareViolation(
            Candidate c, RouteContext facts, Inventory pack, LoadoutSpec loadout, boolean loadoutRepairable) {
        IntSet overdrawn = new IntOpenHashSet();
        c.debits.forEach((item, n) -> {
            if (n > pack.count(item)) {
                overdrawn.add((int) item);
            }
        });
        IntSet hops = new IntOpenHashSet();
        for (int tIdx : c.hopOrder) {
            for (Transport.Consumed d : transports.get(tIdx).consumes(facts.account())) {
                if (overdrawn.isEmpty() || overdrawn.contains(d.item().id())) {
                    hops.add(tIdx);
                }
            }
        }
        if (!overdrawn.isEmpty()) {
            return hops;
        }
        return loadoutRepairable && !c.debits.isEmpty() && !loadout.satisfiedBy(projectPack(pack, Map.of(), c.debits))
                ? hops
                : null;
    }

    /**
     * The lightest direct chain whose fares the pack pays and the loadout survives, or null. Best-first
     * over sets of closed consuming hops: a failing chain is replaced by one child per hop
     * {@link #fareViolation} names, closed on top of the parent's. A child's chain is the lightest under
     * its closures, so the first chain popped that does not fail is the lightest of all.
     */
    private @Nullable Candidate repairFares(
            RouteContext facts,
            DangerField danger,
            boolean[] canUse,
            int[] tCosts,
            Point from,
            Point goal,
            Candidate first,
            Inventory pack,
            LoadoutSpec loadout,
            boolean loadoutRepairable) {
        record Node(Candidate c, int[] closed) {}
        PriorityQueue<Node> open = new PriorityQueue<>((x, y) -> x.c().cost != y.c().cost
                ? Integer.compare(x.c().cost, y.c().cost)
                : Long.compare(x.c().time, y.c().time));
        Set<List<Integer>> seen = new HashSet<>();
        open.add(new Node(first, new int[0]));
        seen.add(List.of());
        while (!open.isEmpty()) {
            Node n = open.poll();
            IntSet culprits = fareViolation(n.c(), facts, pack, loadout, loadoutRepairable);
            if (culprits == null) {
                return n.c();
            }
            for (int hop : culprits) {
                int[] closed = Arrays.copyOf(n.closed(), n.closed().length + 1);
                closed[closed.length - 1] = hop;
                Arrays.sort(closed);
                List<Integer> key = new ArrayList<>(closed.length);
                for (int t : closed) {
                    key.add(t);
                }
                if (!seen.add(key)) {
                    continue;
                }
                boolean[] alt = canUse.clone();
                for (int t : closed) {
                    alt[t] = false;
                }
                Candidate c = directCandidate(facts, danger, alt, tCosts, from, goal);
                if (c != null) {
                    open.add(new Node(c, closed));
                }
            }
        }
        return null;
    }

    private Candidate directCandidate(
            RouteContext facts, DangerField danger, boolean[] canUse, int[] tCosts, Point from, Point goal) {
        long tileBest = Long.MAX_VALUE;
        long tileTime = Long.MAX_VALUE;
        LocalField local = localField(from, goal, danger);
        if (local != null && local.weight(goal.x(), goal.y()) != Integer.MAX_VALUE) {
            tileBest = local.weight(goal.x(), goal.y());
            tileTime = local.time(goal.x(), goal.y());
        }
        Comp comp = tileToGoal(from, goal, klassFor(canUse, tCosts, danger));
        if (tileBest == Long.MAX_VALUE && comp.cost == Long.MAX_VALUE) {
            return null;
        }
        Candidate c = new Candidate();
        // The same choice priceTile makes: the exact walk unless the hierarchy's is a different route, ranked
        // at the lighter of the two weights, the regime every shape is ranked in.
        boolean exact = tileBest != Long.MAX_VALUE;
        boolean lighter = comp.cost < tileBest || (comp.cost == tileBest && comp.time < tileTime);
        List<int[]> chain = lighter ? chain(comp) : null;
        long different = lighter && exact ? differentRouteTime(from, goal, chain, comp.time, tileBest, danger) : -1;
        if (lighter && (!exact || different >= 0)) {
            c.cost = (int) comp.cost;
            c.time = exact ? different : comp.time;
            c.chain = chain;
        } else {
            c.cost = (int) Math.min(tileBest, comp.cost);
            c.time = tileTime;
        }
        seal(c, facts.account());
        return c;
    }

    private static long loadoutMin(LoadoutSpec loadout, int itemId) {
        long min = 0;
        for (LoadoutSpec.Requirement r : loadout.requirements()) {
            if (r instanceof LoadoutSpec.Item it && it.item().id() == itemId) {
                min += it.min();
            }
        }
        return min;
    }

    /**
     * {@code loadout} with the fares still owed folded in as bands, so a settle keeps them instead of
     * shedding what no band names. A fare merges into its item's band, since there is one band per id.
     */
    static LoadoutSpec withFares(LoadoutSpec loadout, Map<Integer, Long> debits) {
        if (debits.isEmpty()) {
            return loadout;
        }
        List<LoadoutSpec.Requirement> out =
                new ArrayList<>(loadout.requirements().size());
        Map<Integer, Long> owed = new LinkedHashMap<>(debits);
        for (LoadoutSpec.Requirement r : loadout.requirements()) {
            // A fare is paid in the item, never in its notes.
            if (r instanceof LoadoutSpec.Item it && !it.noted()) {
                Long fare = owed.remove(it.item().id());
                if (fare != null) {
                    out.add(it.withBand(
                            it.min() + fare,
                            it.target() == Long.MAX_VALUE ? Long.MAX_VALUE : it.target() + fare,
                            it.max() == Long.MAX_VALUE ? Long.MAX_VALUE : it.max() + fare));
                    continue;
                }
            }
            out.add(r);
        }
        // An id no band named: the fare is its whole band.
        owed.forEach((id, fare) -> out.add(new LoadoutSpec.Item(ItemId.getById(id), fare, fare, fare, false)));
        return new LoadoutSpec(
                out, loadout.bankWhenFull(), loadout.anything(), loadout.reserve(), loadout.banksUnnamed());
    }

    /**
     * Shape C, searched to completion: the trip priced under the banked class through the best bank,
     * then repaired ({@link #repairFunded}). A repair that fails names the hops to blame, and the trip
     * is priced again with each closed, lightest floor first ({@link Attempt}). An attempt's floor is
     * its unrepaired weight, closing hops only raises it and a one-stop repair keeps it, so the first
     * repair to succeed is the lightest unless it needed a second stop; at most
     * {@link #FUNDED_RESEARCH_BUDGET} attempts are priced.
     */
    private Candidate fundedCandidate(
            PlanRequest request,
            Resolved r,
            DangerField danger,
            boolean[] canUse,
            int[] tCosts,
            Point from,
            Point goal,
            boolean fareStarved,
            boolean[] capped) {
        if (bankNodes.length == 0) {
            return null;
        }
        ensureBanked(r, request.policy());
        // The bank opens nothing and the direct fares were affordable: nothing to fund.
        if (!r.relaxed && !fareStarved) {
            return null;
        }
        Klass klassB;
        if (r.relaxed) {
            klassB = r.klassB;
        } else {
            boolean[] canUseB = r.canUseBanked.clone();
            klassB = bankedKlass(r, canUseB, costsFor(request.policy(), canUseB), danger);
        }
        Klass carried = klassFor(canUse, tCosts, danger);
        Comp[] toBank0 = tileToBanks(from, carried);
        int[] stops = stopCosts(request.policy());

        PriorityQueue<Attempt> open = new PriorityQueue<>(Attempt.LIGHTEST);
        Set<Closures> seen = new HashSet<>();
        Closures nothing = new Closures(new IntOpenHashSet(), new IntOpenHashSet());
        seen.add(nothing);
        Attempt first = attempt(nothing, canUse, tCosts, carried, klassB, danger, from, goal, toBank0, stops);
        if (first != null) {
            open.add(first);
        }
        int priced = 1;
        while (!open.isEmpty()) {
            Attempt a = open.poll();
            Repair rep = repairFunded(request, r, danger, a, from, goal);
            if (rep.candidate() != null) {
                return rep.candidate();
            }
            for (int i = 0; i < rep.closeBoth().size() + rep.closeCarried().size(); i++) {
                boolean both = i < rep.closeBoth().size();
                int hop = both
                        ? rep.closeBoth().getInt(i)
                        : rep.closeCarried().getInt(i - rep.closeBoth().size());
                Closures closed = a.closed().plus(hop, both);
                if (!seen.add(closed)) {
                    continue;
                }
                if (priced == FUNDED_RESEARCH_BUDGET) {
                    fundedResearchCapped.incrementAndGet();
                    capped[0] = true;
                    continue;
                }
                priced++;
                fundedResearches.incrementAndGet();
                Attempt next = attempt(closed, canUse, tCosts, carried, klassB, danger, from, goal, toBank0, stops);
                if (next != null) {
                    open.add(next);
                }
            }
        }
        return null;
    }

    /**
     * The hops one attempt closes to the leg in (the carried class) and to the leg on (the granted
     * class). A hop the trip must not take is closed to both; one the purse alone cannot afford, to the
     * leg in only: the stop belongs before it.
     */
    private record Closures(IntOpenHashSet carried, IntOpenHashSet granted) {
        Closures plus(int hop, boolean both) {
            IntOpenHashSet c = new IntOpenHashSet(carried);
            c.add(hop);
            IntOpenHashSet g = granted;
            if (both) {
                g = new IntOpenHashSet(granted);
                g.add(hop);
            }
            return new Closures(c, g);
        }
    }

    /**
     * Closure sets the funded search prices per plan, the first included: a guard against a chain of
     * blamed hops turning one plan into many searches. A trip that exhausts it is no route, and says so.
     */
    private static final int FUNDED_RESEARCH_BUDGET = 16;

    private final AtomicLong fundedResearches = new AtomicLong();
    private final AtomicLong fundedResearchCapped = new AtomicLong();

    /**
     * One closure set priced: the hops closed, the carried and granted classes without them, and the
     * lightest bank pair under those, whose weight is a floor under any route its repair makes.
     *
     * @param open the carried class's vector as the account has it, which decides which hops need a grant
     */
    private record Attempt(Closures closed, boolean[] open, Klass carried, Klass granted, BankPair pair) {
        static final Comparator<Attempt> LIGHTEST =
                Comparator.<Attempt>comparingLong(a -> a.pair.weight()).thenComparingLong(a -> a.pair.time());
    }

    private @Nullable Attempt attempt(
            Closures closed,
            boolean[] canUse0,
            int[] tCosts,
            Klass carried0,
            Klass granted0,
            DangerField danger,
            Point from,
            Point goal,
            Comp[] toBank0,
            int[] stops) {
        boolean[] canUse = canUse0;
        Klass carried = carried0;
        Comp[] toBank = toBank0;
        boolean[] narrowed = narrowed(canUse0, closed.carried());
        if (narrowed != null) {
            canUse = narrowed;
            carried = new Klass(this, canUse, tCosts, danger, carried0);
            toBank = tileToBanks(from, carried);
        }
        Klass granted = granted0;
        narrowed = narrowed(granted0.canUse, closed.granted());
        if (narrowed != null) {
            granted = new Klass(this, narrowed, granted0.tCosts, danger, granted0);
        }
        BankPair pair = bestBankPair(toBank, banksToGoal(goal, granted), stops);
        return pair == null ? null : new Attempt(closed, canUse0, carried, granted, pair);
    }

    private static boolean[] narrowed(boolean[] canUse, IntOpenHashSet closed) {
        boolean[] out = null;
        for (int hop : closed) {
            if (canUse[hop]) {
                if (out == null) {
                    out = canUse.clone();
                }
                out[hop] = false;
            }
        }
        return out;
    }

    /**
     * One chain's repair: the candidate it made, or the hops to close, to both legs or the leg in; neither
     * for a dead end.
     */
    private record Repair(@Nullable Candidate candidate, IntArrayList closeBoth, IntArrayList closeCarried) {
        static Repair of(Candidate c) {
            return new Repair(c, new IntArrayList(), new IntArrayList());
        }

        static Repair closeBoth(IntArrayList hops) {
            return new Repair(null, hops, new IntArrayList());
        }

        static Repair closeBoth(int hop) {
            return closeBoth(IntArrayList.of(hop));
        }

        static Repair closeCarried(int hop) {
            return new Repair(null, new IntArrayList(), IntArrayList.of(hop));
        }
    }

    /**
     * Attempt {@code a}'s chain repaired: each hop the carried class refuses gets its gate itemized
     * against the bank, the chain's fares must be fundable, and the stop settles the arrival loadout
     * keeping what the gates past it read ({@link #planStop}), else a second, deposit stop sheds it past
     * the gated section. Where it fails, it names what to close.
     */
    private Repair repairFunded(
            PlanRequest request, Resolved r, DangerField danger, Attempt a, Point from, Point goal) {
        RouteContext facts = r.facts;
        Candidate c = assemble(
                a.pair().weight(),
                a.pair().time(),
                request.account(),
                chain(a.pair().to()),
                chain(a.pair().on()));
        Inventory pack = request.account().inventory();
        Bank bank = request.account().bank();

        // The leg in pays from the purse alone: the hop it runs out at goes to the leg in, so the stop moves
        // ahead of it.
        int overdrawn = c.ledger.firstOverdrawn(0, pastStop(c.bankChainIndex), pack);
        if (overdrawn >= 0) {
            return Repair.closeCarried(overdrawn);
        }

        LinkedHashMap<Integer, Long> grants = new LinkedHashMap<>();
        for (int k = 0; k < c.hopOrder.size(); k++) {
            int tIdx = c.hopOrder.get(k);
            if (a.open()[tIdx]) {
                continue;
            }
            if (!grantFor(tIdx, facts, pack, bank, grants)) {
                return Repair.closeBoth(tIdx);
            }
        }
        for (Map.Entry<Integer, Long> e : c.debits.entrySet()) {
            int id = e.getKey();
            // The fare and the loadout's own floor for the item, from what the bank has left after the gates'.
            long shortfall =
                    e.getValue() + loadoutMin(request.loadout(), id) - pack.count(id) - grants.getOrDefault(id, 0L);
            if (shortfall > 0 && drawableStock(pack, bank, id) - grants.getOrDefault(id, 0L) < shortfall) {
                return Repair.closeBoth(c.ledger.spenders(id));
            }
        }
        Map<Integer, Long> floors = new LinkedHashMap<>();
        grants.forEach((id, n) -> floors.put(id, pack.count(id) + n));
        Inventory atStop = projectPack(pack, Map.of(), c.ledger.before(c.bankChainIndex));
        StopPlan stop = planStop(request, c, c.bankChainIndex, atStop, bank, floors);
        c.stopManifest = stop.manifest();
        if (c.stopManifest != null) {
            return Repair.of(c);
        }
        Candidate twoStops = depositShape(request, r, danger, a, pack, bank, from, goal, c.debits);
        if (twoStops != null) {
            return Repair.of(twoStops);
        }
        if (!stop.unfit().isEmpty()) {
            return Repair.closeBoth(stop.unfit());
        }
        Segment past = stop.past();
        if (past == null) {
            // The mins and the room the gates past ask do not fit the pack together.
            return Repair.closeBoth(roomShortPast(c, c.bankChainIndex, atStop));
        }
        if (past.shut() >= 0) {
            return Repair.closeBoth(past.shut());
        }
        if (past.overdrawn() >= 0) {
            return Repair.closeBoth(past.overdrawn());
        }
        return Repair.closeBoth(offendingHops(request.loadout(), c, grants.keySet(), past.end(), hitsOf(request)));
    }

    /**
     * A stop planned: its manifest, null where its settle cannot meet the mins or the walk past it fails
     * or arrives outside the loadout; the walk past it, null where the settle could not meet the mins;
     * and the gates past it whose reopening did not fit beside the mins, one of which must go.
     */
    private record StopPlan(
            @Nullable Manifest manifest, @Nullable Segment past, IntArrayList unfit) {}

    /** What a stop's settle is held to beyond the loadout: floors, caps, and the items whose notes it banks. */
    private record StopTerms(Map<Integer, Long> floors, Map<Integer, Long> caps, Set<Integer> notesBanked) {}

    /**
     * The stop at chain index {@code k} of {@code c}: the arrival loadout settled over {@code atStop}, the
     * pack as the booth opens, with the fares past it folded in, each item of {@code floors} held to at
     * least its count, and the room the gates past it ask kept free. For a gate past it that is shut, the
     * stop also banks what the gate refuses and draws what opens it, and is settled again.
     */
    private StopPlan planStop(
            PlanRequest request, Candidate c, int k, Inventory atStop, Bank bank, Map<Integer, Long> floors) {
        StopTerms terms = new StopTerms(new LinkedHashMap<>(floors), new LinkedHashMap<>(), new HashSet<>());
        IntArrayList reopened = new IntArrayList();
        while (true) {
            LoadoutSpec spec = withCaps(
                    keepingCarried(
                            withFloors(withFares(request.loadout(), c.ledger.after(k)), terms.floors()),
                            request.loadout(),
                            atStop),
                    terms,
                    request.loadout(),
                    atStop);
            Settle settle = settleKeepingRoom(spec, atStop, bank, c, k, request);
            if (!settle.satisfiable()) {
                return new StopPlan(null, null, reopened);
            }
            Inventory leaving = settle.projected();
            Segment past = walkPast(c, k, leaving, request);
            if (past.shut() >= 0
                    && !reopened.contains(past.shut())
                    && reopen(past.shut(), request, atStop, bank, leaving, past.atShut(), terms)) {
                reopened.add(past.shut());
                continue;
            }
            boolean arrives = past.open() && request.loadout().satisfiedBy(past.end());
            return new StopPlan(arrives ? settle.manifest() : null, past, new IntArrayList());
        }
    }

    /**
     * What the stop that left {@code leaving} must also do for gate {@code tag}, met with {@code atGate},
     * to open, added to {@code terms}: bank what the gate refuses, and draw what it lacks (one arm of an
     * OR gate will do). False when the gate refuses nothing and no grant opens it.
     */
    private boolean reopen(
            int tag,
            PlanRequest request,
            Inventory atStop,
            Bank bank,
            Inventory leaving,
            Inventory atGate,
            StopTerms terms) {
        Refusal refused = refusedBy(tag, atGate);
        Map<Integer, Long> back = new LinkedHashMap<>();
        RouteContext facts = new RouteContext(request.account().withInventory(atGate), request.policy(), serverData);
        boolean draws = grantFor(tag, facts, atGate, bankAfter(bank, atStop, leaving), back) && !back.isEmpty();
        // The gate's counts, moved back to the stop over what the hops between spend.
        refused.most()
                .forEach((id, most) ->
                        terms.caps().merge(id, Math.max(0, leaving.count(id) - (atGate.count(id) - most)), Long::min));
        terms.notesBanked().addAll(refused.notes());
        if (draws) {
            back.forEach((id, n) -> terms.floors().merge(id, leaving.count(id) + n, Long::max));
        }
        return draws || !refused.most().isEmpty();
    }

    /**
     * What a gate refuses of a pack: the most of each item it allows, counted as the item, and the items
     * whose notes it refuses too.
     */
    private record Refusal(Map<Integer, Long> most, Set<Integer> notes) {}

    private Refusal refusedBy(int tag, Inventory pack) {
        Map<Integer, Long> most = new LinkedHashMap<>();
        Set<Integer> notes = new HashSet<>();
        for (Fact f : uniquePrereqs[prereqOf[tag]].facts()) {
            Map<Integer, Long> refused = f.refused(pack, serverData);
            refused.forEach((id, n) -> most.merge(id, n, Long::min));
            if (f.refusesNotes()) {
                for (int id : refused.keySet()) {
                    if (pack.count(NoteForm.NOTED, id) > 0) {
                        notes.add(id);
                    }
                }
            }
        }
        return new Refusal(most, notes);
    }

    /** {@code pack} with what {@code refused} names banked. */
    private Inventory withoutRefused(Inventory pack, Refusal refused) {
        Map<Integer, Long> banked = new LinkedHashMap<>();
        refused.most().forEach((id, most) -> {
            if (pack.count(id) > most) {
                banked.put(id, pack.count(id) - most);
            }
        });
        Inventory out = banked.isEmpty() ? pack : projectPack(pack, Map.of(), banked);
        if (refused.notes().isEmpty()) {
            return out;
        }
        List<InventorySlot> kept = new ArrayList<>(out.slots().size());
        for (InventorySlot s : out.slots()) {
            if (!s.noted() || !refused.notes().contains(s.item().id())) {
                kept.add(s);
            }
        }
        return Inventory.of(kept, out.wornOffPack());
    }

    /**
     * {@code spec} holding each item of the caps in {@code terms} to at most that many, and each of its
     * banked notes to none, never below a min {@code loadout} insists on: a band of its own keeps what
     * {@code held} carries up to the cap where it keeps what no band names, and none of it otherwise.
     */
    private static LoadoutSpec withCaps(LoadoutSpec spec, StopTerms terms, LoadoutSpec loadout, Inventory held) {
        if (terms.caps().isEmpty() && terms.notesBanked().isEmpty()) {
            return spec;
        }
        List<LoadoutSpec.Requirement> out = new ArrayList<>(spec.requirements().size()
                + terms.caps().size()
                + terms.notesBanked().size());
        Map<Integer, Long> left = new LinkedHashMap<>(terms.caps());
        Set<Integer> notesLeft = new HashSet<>(terms.notesBanked());
        for (LoadoutSpec.Requirement r : spec.requirements()) {
            if (r instanceof LoadoutSpec.Item it) {
                int id = it.item().id();
                Long cap = it.noted() ? (notesLeft.remove(id) ? Long.valueOf(0) : null) : left.remove(id);
                if (cap != null) {
                    long most = Math.max(it.min(), cap);
                    out.add(it.withBand(it.min(), Math.min(it.target(), most), Math.min(it.max(), most)));
                    continue;
                }
            }
            out.add(r);
        }
        left.forEach((id, cap) -> {
            long keep = loadout.keepsUnnamed() ? Math.min(cap, held.count(id)) : 0;
            out.add(new LoadoutSpec.Item(ItemId.getById(id), 0, keep, cap, false));
        });
        notesLeft.forEach(id -> out.add(new LoadoutSpec.Item(ItemId.getById(id), 0, 0, 0, false, true)));
        return new LoadoutSpec(out, spec.bankWhenFull(), spec.anything(), spec.reserve(), spec.banksUnnamed());
    }

    /**
     * Where {@code loadout} keeps what no band names, the bands {@code spec} adds for ids it does not name keep what
     * {@code held} carries of them, as the catch-all would: a fare or a floor sets a minimum, not a cap.
     */
    private static LoadoutSpec keepingCarried(LoadoutSpec spec, LoadoutSpec loadout, Inventory held) {
        if (!loadout.keepsUnnamed() || spec == loadout) {
            return spec;
        }
        List<LoadoutSpec.Requirement> out = new ArrayList<>(spec.requirements().size());
        boolean raised = false;
        for (LoadoutSpec.Requirement r : spec.requirements()) {
            if (r instanceof LoadoutSpec.Item it
                    && !it.noted()
                    && !loadout.named(it.item().id())) {
                long carried = held.count(it.item().id());
                if (carried > it.target()) {
                    out.add(it.withBand(it.min(), carried, Math.max(it.max(), carried)));
                    raised = true;
                    continue;
                }
            }
            out.add(r);
        }
        return raised
                ? new LoadoutSpec(out, spec.bankWhenFull(), spec.anything(), spec.reserve(), spec.banksUnnamed())
                : spec;
    }

    /**
     * {@code loadout} with each item of {@code floors} held to at least that many of it, never its notes:
     * a floor raises its item's band, or is a band of its own.
     */
    static LoadoutSpec withFloors(LoadoutSpec loadout, Map<Integer, Long> floors) {
        if (floors.isEmpty()) {
            return loadout;
        }
        List<LoadoutSpec.Requirement> out =
                new ArrayList<>(loadout.requirements().size() + floors.size());
        Map<Integer, Long> left = new LinkedHashMap<>(floors);
        for (LoadoutSpec.Requirement r : loadout.requirements()) {
            if (r instanceof LoadoutSpec.Item it && !it.noted()) {
                Long floor = left.remove(it.item().id());
                if (floor != null) {
                    out.add(it.withBand(
                            Math.max(it.min(), floor), Math.max(it.target(), floor), Math.max(it.max(), floor)));
                    continue;
                }
            }
            out.add(r);
        }
        left.forEach((id, floor) -> out.add(new LoadoutSpec.Item(ItemId.getById(id), floor, floor, floor, false)));
        return new LoadoutSpec(
                out, loadout.bankWhenFull(), loadout.anything(), loadout.reserve(), loadout.banksUnnamed());
    }

    /**
     * The hops of {@code c} whose gates read something the arrival holds and the loadout refuses: a
     * chain that does not read the item has no reason to carry it.
     */
    private IntArrayList offendingHops(
            LoadoutSpec loadout, Candidate c, Set<Integer> granted, Inventory arrival, int hits) {
        IntOpenHashSet offending = new IntOpenHashSet();
        GateItems read = gateItems(c, 0, c.chain.size(), hits);
        for (InventorySlot s : arrival.slots()) {
            int id = s.item().id();
            if ((granted.contains(id) || read.reads(id, serverData))
                    && !loadout.tolerates(id, arrival.count(id), serverData)) {
                offending.add(id);
            }
        }
        IntArrayList culprits = new IntArrayList();
        if (offending.isEmpty()) {
            return culprits;
        }
        for (int tIdx : c.hopOrder) {
            if (!culprits.contains(tIdx) && gateReadsAny(tIdx, offending, hits)) {
                culprits.add(tIdx);
            }
        }
        return culprits;
    }

    /**
     * Whether transport {@code tIdx}'s gate reads any of {@code ids}: by id, as a melee weapon, or as a
     * meal where its Hits gate is above {@code hits}.
     */
    private boolean gateReadsAny(int tIdx, IntSet ids, int hits) {
        for (Fact f : uniquePrereqs[prereqOf[tIdx]].facts()) {
            switch (f.kind()) {
                case CARRIED_AT_LEAST, WEARING -> {
                    if (ids.contains(f.itemId())) {
                        return true;
                    }
                }
                case CARRYING_MELEE_WEAPON -> {
                    for (int id : ids) {
                        if (MeleeWeapons.isMelee(serverData, id)) {
                            return true;
                        }
                    }
                }
                case SKILL_AT_LEAST -> {
                    if (f.foodLifts() && hits < f.threshold()) {
                        for (int id : ids) {
                            if (Meals.IDS.contains(id)) {
                                return true;
                            }
                        }
                    }
                }
                default -> {}
            }
        }
        return false;
    }

    private static int hitsOf(PlanRequest request) {
        return request.account().stats().level(Skill.HITS);
    }

    private Candidate depositShape(
            PlanRequest request,
            Resolved r,
            DangerField danger,
            Attempt a,
            Inventory pack,
            Bank bank,
            Point from,
            Point goal,
            Map<Integer, Long> chainFares) {
        RouteContext facts = r.facts;
        BankPair withdraw = a.pair();
        boolean[] canUse = a.open();
        // Leg 2a, withdraw bank to deposit bank, under the granted class; leg 2b, on to the goal, under the
        // class of the pack the deposit stop leaves. That pack is first priced with every fare the account owns
        // aboard, which opens a hop gated on carrying its own fare but can overfill it and close a gate on free
        // room; when no deposit bank reaches the goal, it is priced again as the arrival loadout with the chain's
        // fares folded in.
        Inventory leaves = request.loadout().settleAt(pack, bank, serverData).projected();
        LoadoutSpec owedSpec = withFares(request.loadout(), chainFares);
        Inventory owed = owedSpec.settleAt(pack, bank, serverData).projected();
        List<Inventory> classPacks = new ArrayList<>(3);
        classPacks.add(withFareFloat(leaves, pack, bank, request.account()));
        classPacks.add(owed);
        Comp[] midComp = tileToBanks(nodePoint(bankNodes[withdraw.bank()]), a.granted());
        int[] stops = stopCosts(request.policy());
        BankPair deposit = null;
        Comp[] backComp = null;
        for (int k = 0; k < classPacks.size(); k++) {
            Inventory classPack = classPacks.get(k);
            Klass settled = settledKlass(r, request.policy(), danger, classPack, a.carried());
            boolean[] narrowed = narrowed(settled.canUse, a.closed().granted());
            if (narrowed != null) {
                settled = new Klass(this, narrowed, settled.tCosts, danger, settled);
            }
            backComp = banksToGoal(goal, settled);
            deposit = bestBankPair(midComp, backComp, stops);
            if (deposit != null) {
                break;
            }
            if (k == 1) {
                // Last, the owed pack keeping the most room any gate asks, so a full one does not shut them all.
                int room = roomAnyGateWants(owed);
                Settle roomy = room > owed.emptySlots() ? owedSpec.settleAt(pack, bank, serverData, room) : null;
                if (roomy != null && roomy.satisfiable()) {
                    classPacks.add(roomy.projected());
                }
            }
        }
        if (deposit == null) {
            return null;
        }
        long toWithdraw = withdraw.to().cost + stops[withdraw.bank()];
        long toWithdrawTime = withdraw.to().time + stops[withdraw.bank()];
        Candidate c = assemble(
                toWithdraw + deposit.weight(),
                toWithdrawTime + deposit.time(),
                request.account(),
                chain(withdraw.to()),
                chain(deposit.to()),
                chain(deposit.on()));
        if (deposit.bank() == withdraw.bank()) {
            // Both stops at one booth are one visit to it.
            c.cost -= stops[deposit.bank()];
            c.time -= stops[deposit.bank()];
        }
        LinkedHashMap<Integer, Long> grants = new LinkedHashMap<>();
        for (int i = c.bankChainIndex + 1; i <= c.bankChainIndex2; i++) {
            int tag = c.chain.get(i)[1];
            if (tag >= 0 && !canUse[tag] && !grantFor(tag, facts, pack, bank, grants)) {
                return null;
            }
        }
        Map<Integer, Long> debits = c.debits;
        for (Map.Entry<Integer, Long> e : debits.entrySet()) {
            long have = pack.count(e.getKey()) + grants.getOrDefault(e.getKey(), 0L);
            long shortfall = e.getValue() - have;
            if (shortfall > 0) {
                if (drawableStock(pack, bank, e.getKey()) - grants.getOrDefault(e.getKey(), 0L) < shortfall) {
                    return null;
                }
                grants.merge(e.getKey(), shortfall, Long::sum);
            }
        }
        if (grants.isEmpty()) {
            // Nothing to draw: the very state this shape leaves once its first stop is done.
            return depositOnly(request, a, pack, bank, from, backComp);
        }
        int k1 = c.bankChainIndex;
        int k2 = c.bankChainIndex2;
        Inventory atStop1 = projectPack(pack, Map.of(), c.ledger.before(k1));
        // What no contract claims sheds at the withdraw stop, freeing slots the displacement would take.
        Map<Integer, Long> shed = shedAtWithdrawStop(request.loadout(), c, atStop1, grants, debits, hitsOf(request));
        // Stop one makes the room the section up to stop two asks; stop two makes the rest.
        int need = slotsNeeded(atStop1, grants)
                + walkSegment(c, pastStop(k1), pastStop(k2), grantedView(atStop1, grants), request)
                        .room();
        // Stop one keeps what the gates up to stop two read; stop two draws back what the gates past it read.
        IntOpenHashSet kept = new IntOpenHashSet(shed.keySet());
        GateItems section = gateItems(c, pastStop(k1), pastStop(k2), hitsOf(request));
        for (InventorySlot s : atStop1.slots()) {
            if (section.reads(s.item().id(), serverData)) {
                kept.add(s.item().id());
            }
        }
        Map<Integer, Long> displaced =
                displacementFor(atStop1, need - atStop1.emptySlots() - shedSlots(atStop1, shed), grants, debits, kept);
        if (displaced == null) {
            return null;
        }
        if (!shed.isEmpty()) {
            LinkedHashMap<Integer, Long> both = new LinkedHashMap<>(displaced);
            both.putAll(shed);
            displaced = both;
        }
        Manifest stop1 = grantStop(atStop1, bank, grants, displaced);
        Inventory leaving1 = Packs.apply(atStop1, stop1, serverData);
        Segment between = walkSegment(c, pastStop(k1), pastStop(k2), leaving1, request);
        if (!between.open()) {
            return null;
        }
        // Stop two is the arrival loadout's own stop over the pack and bank stop one leaves, so a plan made
        // again with the first stop done finds this same trip.
        Manifest stop2 = planStop(request, c, k2, between.end(), bankAfter(bank, atStop1, leaving1), Map.of())
                .manifest();
        if (stop2 == null) {
            return null;
        }
        c.stopManifest = stop1;
        c.stopManifest2 = stop2;

        return c;
    }

    /**
     * {@link #depositShape} without its withdraw stop: the carried leg straight to the deposit bank,
     * whose stop settles the arrival loadout with the fares still owed folded in.
     */
    private @Nullable Candidate depositOnly(
            PlanRequest request, Attempt a, Inventory pack, Bank bank, Point from, Comp[] backComp) {
        BankPair pair = bestBankPair(tileToBanks(from, a.carried()), backComp, stopCosts(request.policy()));
        if (pair == null) {
            return null;
        }
        Candidate c = assemble(pair.weight(), pair.time(), request.account(), chain(pair.to()), chain(pair.on()));
        if (c.ledger.firstOverdrawn(0, pastStop(c.bankChainIndex), pack) >= 0) {
            return null;
        }
        Inventory atStop = projectPack(pack, Map.of(), c.ledger.before(c.bankChainIndex));
        c.stopManifest =
                planStop(request, c, c.bankChainIndex, atStop, bank, Map.of()).manifest();
        return c.stopManifest == null ? null : c;
    }

    /**
     * {@code settled} with every item a transport can take from {@code account} raised to all of it owned:
     * the most a stop could hold.
     */
    private Inventory withFareFloat(Inventory settled, Inventory pack, Bank bank, AccountFacts account) {
        LinkedHashSet<Integer> fares = new LinkedHashSet<>();
        for (Transport t : transports) {
            for (Transport.Consumed d : t.consumes(account)) {
                fares.add(d.item().id());
            }
        }
        return fareFloat(settled, pack, bank, fares, serverData);
    }

    /**
     * {@link #withFareFloat} over {@code fares}: each raised to everything owned of it, notes included,
     * less what {@code settled} holds.
     */
    static Inventory fareFloat(Inventory settled, Inventory pack, Bank bank, Iterable<Integer> fares, ServerData sd) {
        Map<Integer, Long> raise = new LinkedHashMap<>();
        for (int id : fares) {
            long extra = pack.count(NoteForm.BOTH, id) + bank.count(id) - settled.count(NoteForm.BOTH, id);
            if (extra > 0) {
                raise.putIfAbsent(id, extra);
            }
        }
        return raise.isEmpty() ? settled : Packs.adjust(settled, raise, Map.of(), sd);
    }

    /**
     * What the withdraw stop can bank on the spot: pack items no contract between here and the goal
     * claims: no fare, no grant, nothing the arrival loadout names, and none of the chain's gate items,
     * since a credential carried in is among neither the grants nor the fares. Worn slots never shed, nor
     * notes, which a manifest's targets do not bank.
     */
    private Map<Integer, Long> shedAtWithdrawStop(
            LoadoutSpec arrival,
            Candidate c,
            Inventory pack,
            Map<Integer, Long> grants,
            Map<Integer, Long> debits,
            int hits) {
        IntOpenHashSet keep = new IntOpenHashSet();
        keep.addAll(grants.keySet());
        keep.addAll(debits.keySet());
        GateItems gates = gateItems(c, pastStop(c.bankChainIndex), c.chain.size(), hits);
        LinkedHashMap<Integer, Long> shed = new LinkedHashMap<>();
        for (InventorySlot s : pack.slots()) {
            int id = s.item().id();
            if (s.equipped() || s.noted() || keep.contains(id) || arrival.named(id) || gates.reads(id, serverData)) {
                continue;
            }
            shed.merge(id, (long) s.amount(), Long::sum);
        }
        return shed;
    }

    /** What a chain's gates read off the pack: ids, and whether any melee weapon or any meal will do. */
    private record GateItems(IntOpenHashSet ids, boolean melee, boolean meals) {
        boolean reads(int id, ServerData defs) {
            return ids.contains(id) || (melee && MeleeWeapons.isMelee(defs, id)) || (meals && Meals.IDS.contains(id));
        }
    }

    /**
     * The {@link GateItems} of the hops at chain indices {@code from} up to {@code to}; a Hits gate above
     * {@code hits} reads every meal.
     */
    private GateItems gateItems(Candidate c, int from, int to, int hits) {
        IntOpenHashSet ids = new IntOpenHashSet();
        boolean melee = false;
        boolean meals = false;
        for (int i = from; i < to && i < c.chain.size(); i++) {
            int tag = c.chain.get(i)[1];
            if (tag < 0) {
                continue;
            }
            for (Fact f : uniquePrereqs[prereqOf[tag]].facts()) {
                switch (f.kind()) {
                    case CARRIED_AT_LEAST, WEARING -> ids.add(f.itemId());
                    case CARRYING_MELEE_WEAPON -> melee = true;
                    case SKILL_AT_LEAST -> meals |= f.foodLifts() && hits < f.threshold();
                    default -> {}
                }
            }
        }
        return new GateItems(ids, melee, meals);
    }

    private int shedSlots(Inventory pack, Map<Integer, Long> shed) {
        if (shed.isEmpty()) {
            return 0;
        }
        int freed = 0;
        for (InventorySlot s : pack.slots()) {
            if (!s.equipped() && !s.noted() && shed.containsKey(s.item().id())) {
                freed++;
            }
        }
        return freed;
    }

    /**
     * {@code bank} after a stop that turned {@code pack} into {@code leaving}: what it drew out, what it
     * banked in. What it banked comes first, so a gate any item of a kind opens takes back what was carried.
     */
    private static Bank bankAfter(Bank bank, Inventory pack, Inventory leaving) {
        Set<Integer> ids = new LinkedHashSet<>(pack.itemIds());
        ids.addAll(leaving.itemIds());
        Map<Integer, Long> deltas = new LinkedHashMap<>();
        for (int id : ids) {
            long delta = pack.count(NoteForm.BOTH, id) - leaving.count(NoteForm.BOTH, id);
            if (delta != 0) {
                deltas.put(id, delta);
            }
        }
        if (deltas.isEmpty()) {
            return bank;
        }
        LinkedHashMap<Integer, Long> counts = new LinkedHashMap<>();
        deltas.forEach((id, delta) -> {
            if (delta > 0) {
                counts.put(id, 0L);
            }
        });
        for (BankSlot s : bank.slots()) {
            counts.merge(s.item().id(), (long) s.amount(), Long::sum);
        }
        deltas.forEach((id, delta) -> counts.merge(id, delta, Long::sum));
        MutableBank out = new MutableBank();
        out.setCapacity(bank.capacity());
        int i = 0;
        for (Map.Entry<Integer, Long> e : counts.entrySet()) {
            if (e.getValue() > 0) {
                out.setSlot(i, new BankSlot(i, ItemId.getById(e.getKey()), (int)
                        Math.min(Integer.MAX_VALUE, e.getValue())));
                i++;
            }
        }
        return out.snapshot();
    }

    /**
     * What a stop can draw of {@code id} as the item: the bank's stock, and the notes of it the pack
     * carries, banked to come back out.
     */
    private static long drawableStock(Inventory pack, Bank bank, int id) {
        return bank.count(id) + pack.count(NoteForm.NOTED, id);
    }

    /**
     * A grant stop's manifest over {@code pack} as the booth opens: {@code grants} drawn,
     * {@code displaced} banked, and, where the bank cannot cover a grant, that many of the item's notes
     * banked to come back out as the item.
     */
    private static Manifest grantStop(
            Inventory pack, Bank bank, Map<Integer, Long> grants, Map<Integer, Long> displaced) {
        Map<ItemId, Long> notes = new EnumMap<>(ItemId.class);
        for (Map.Entry<Integer, Long> g : grants.entrySet()) {
            int id = g.getKey();
            long noted = pack.count(NoteForm.NOTED, id);
            long fromNotes = Math.min(noted, Math.max(0, g.getValue() - bank.count(id)));
            if (fromNotes > 0) {
                notes.put(ItemId.getById(id), noted - fromNotes);
            }
        }
        return new Manifest(targetMap(pack, grants, true), targetMap(pack, displaced, false), Set.of(), notes);
    }

    /** Deltas as absolute pack targets: the pack's count raised, or lowered and floored at 0. */
    private static EnumMap<ItemId, Long> targetMap(Inventory pack, Map<Integer, Long> deltas, boolean raise) {
        EnumMap<ItemId, Long> out = new EnumMap<>(ItemId.class);
        deltas.forEach((id, n) ->
                out.put(ItemId.getById(id), raise ? pack.count(id) + n : Math.max(0, pack.count(id) - n)));
        return out;
    }

    private int slotsNeeded(Inventory pack, Map<Integer, Long> grants) {
        int need = 0;
        for (Map.Entry<Integer, Long> e : grants.entrySet()) {
            boolean stack = serverData.isStackable(e.getKey());
            need += stack ? (pack.packCount(e.getKey()) > 0 ? 0 : 1) : (int) Math.min(30, e.getValue());
        }
        return need;
    }

    /**
     * When the withdrawal does not fit beside the pack, the slots to bank: never a worn one, a note
     * (which a manifest's targets do not bank), a grant, a fare or an id in {@code kept}, cheapest
     * first, whole slots. {@code Map.of()} when nothing need move, null when even every
     * such slot frees too little.
     */
    private @Nullable Map<Integer, Long> displacementFor(
            Inventory pack, int deficit, Map<Integer, Long> grants, Map<Integer, Long> debits, IntSet kept) {
        if (deficit <= 0) {
            return Map.of();
        }
        List<InventorySlot> eligible = new ArrayList<>();
        for (InventorySlot s : pack.slots()) {
            int id = s.item().id();
            if (s.equipped() || s.noted() || grants.containsKey(id) || debits.containsKey(id) || kept.contains(id)) {
                continue;
            }
            eligible.add(s);
        }
        return cheapestSlots(eligible, deficit);
    }

    /** The {@code deficit} cheapest whole slots of {@code eligible} as counts by id, or null when there are fewer. */
    private @Nullable Map<Integer, Long> cheapestSlots(List<InventorySlot> eligible, int deficit) {
        if (eligible.size() < deficit) {
            return null;
        }
        List<InventorySlot> byValue = new ArrayList<>(eligible);
        byValue.sort(Comparator.comparingLong(this::slotValue));
        LinkedHashMap<Integer, Long> victims = new LinkedHashMap<>();
        for (int i = 0; i < deficit; i++) {
            InventorySlot v = byValue.get(i);
            victims.merge(v.item().id(), (long) v.amount(), Long::sum);
        }
        return victims;
    }

    private long basePrice(int itemId) {
        ItemDef def = serverData.item(itemId);
        return def == null ? Long.MAX_VALUE : def.basePrice();
    }

    /** What banking {@code s} gives up: its item's base price, a stack's for all of it. */
    private long slotValue(InventorySlot s) {
        ItemDef def = serverData.item(s.item().id());
        return def == null ? Long.MAX_VALUE : Math.max(def.basePrice(), (long) def.basePrice() * s.amount());
    }

    /** The hops past chain index {@code chainIndex} that want more free slots than {@code leaving} has. */
    private IntArrayList roomShortPast(Candidate c, int chainIndex, Inventory leaving) {
        IntArrayList hops = new IntArrayList();
        int free = 30 - leaving.usedSlots();
        for (int i = chainIndex <= 0 ? 0 : chainIndex + 1; i < c.chain.size(); i++) {
            int tag = c.chain.get(i)[1];
            if (tag < 0 || hops.contains(tag)) {
                continue;
            }
            for (Fact f : uniquePrereqs[prereqOf[tag]].facts()) {
                if (f.kind() == Fact.Kind.ROOM_FOR
                        && leaving.newSlotsFor(serverData, f.itemId(), f.threshold()) > free) {
                    hops.add(tag);
                    break;
                }
            }
        }
        return hops;
    }

    /** Whether transport {@code tIdx}'s gate opens to {@code ctx} with its room read as made, the stop's to make. */
    private boolean opensBarRoom(int tIdx, RouteContext ctx) {
        int p = prereqOf[tIdx];
        if (roomFacts.length == 0) {
            return uniquePrereqs[p].test(ctx);
        }
        long[] bits = factBits(ctx);
        for (int i : roomFacts) {
            bits[i >>> 6] |= 1L << i;
        }
        return Prereq.run(prereqPrograms[p], bits, new boolean[longestProgram]);
    }

    /** The most free slots any gate on free room asks of {@code pack}. */
    private int roomAnyGateWants(Inventory pack) {
        int want = 0;
        for (int i : roomFacts) {
            Fact f = distinctFacts[i];
            want = (int) Math.max(want, pack.newSlotsFor(serverData, f.itemId(), f.threshold()));
        }
        return want;
    }

    /**
     * {@code loadout} settled over {@code atStop} with the room the gates past chain index {@code chainIndex}
     * want kept free, read off the pack each settle leaves, since the fares it draws decide which stacks
     * the hops before a gate empty.
     */
    private Settle settleKeepingRoom(
            LoadoutSpec loadout, Inventory atStop, Bank bank, Candidate c, int chainIndex, PlanRequest request) {
        Settle settle = loadout.settleAt(atStop, bank, serverData);
        int keep = 0;
        for (int round = 0; round < 3 && settle.satisfiable(); round++) {
            int want = walkPast(c, chainIndex, settle.projected(), request).room();
            if (want <= settle.projected().emptySlots() || want <= keep) {
                break;
            }
            keep = want;
            settle = loadout.settleAt(atStop, bank, serverData, keep);
        }
        return settle;
    }

    /** {@code pack} with the grants added, its slots agreeing with its counts: a gate may read either. */
    private Inventory grantedView(Inventory pack, Map<Integer, Long> grants) {
        return Packs.adjust(pack, grants, Map.of(), serverData);
    }

    /**
     * Itemizes transport {@code tIdx}'s gate against the bank into {@code grants}: one grant alone where
     * one opens it, so an OR gate draws only the arm the bank has, else grantable facts that open it
     * together, none of them spare and the dearest dropped first. The gate is read with what it refuses
     * of {@code carriedPack} banked. False when no grant opens it.
     */
    private boolean grantFor(
            int tIdx, RouteContext carried, Inventory carriedPack, Bank bank, Map<Integer, Long> grants) {
        var prereq = uniquePrereqs[prereqOf[tIdx]];
        Refusal refused = refusedBy(tIdx, carriedPack);
        Inventory pack = withoutRefused(carriedPack, refused);
        RouteContext facts = pack == carriedPack
                ? carried
                : carried.withAccount(carried.account().withInventory(pack));
        LinkedHashMap<Integer, Long> flipped = new LinkedHashMap<>();
        for (Fact f : prereq.facts()) {
            Long g = grantOf(f, facts, pack, bank);
            if (g != null && g > 0) {
                flipped.put(grantItemOf(f, bank), g);
            }
        }
        flipped.remove(-1);
        if (flipped.isEmpty()) {
            // Nothing to draw: the gate opens only if all it lacks is what the stop banks, room included.
            return (roomFacts.length > 0 || pack != carriedPack)
                    && opensBarRoom(tIdx, facts.withAccount(facts.account().withInventory(grantedView(pack, grants))));
        }
        for (Map.Entry<Integer, Long> e : flipped.entrySet()) {
            LinkedHashMap<Integer, Long> one = new LinkedHashMap<>(grants);
            one.merge(e.getKey(), e.getValue(), Long::max);
            if (opensBarRoom(tIdx, facts.withAccount(facts.account().withInventory(grantedView(pack, one))))) {
                grants.merge(e.getKey(), e.getValue(), Long::max);
                return true;
            }
        }
        LinkedHashMap<Integer, Long> all = new LinkedHashMap<>(grants);
        flipped.forEach((id, n) -> all.merge(id, n, Long::max));
        if (!opensBarRoom(tIdx, facts.withAccount(facts.account().withInventory(grantedView(pack, all))))) {
            return false;
        }
        // An AND of OR gates (a bow and arrows) opens on one arm of each: draw no more than that.
        LinkedHashMap<Integer, Long> kept = new LinkedHashMap<>(flipped);
        List<Integer> dearestFirst = new ArrayList<>(flipped.keySet());
        dearestFirst.sort(Comparator.comparingLong(this::basePrice).reversed());
        for (Integer id : dearestFirst) {
            LinkedHashMap<Integer, Long> without = new LinkedHashMap<>(grants);
            kept.forEach((k, n) -> {
                if (!k.equals(id)) {
                    without.merge(k, n, Long::max);
                }
            });
            if (opensBarRoom(tIdx, facts.withAccount(facts.account().withInventory(grantedView(pack, without))))) {
                kept.remove(id);
            }
        }
        kept.forEach((id, n) -> grants.merge(id, n, Long::max));
        return true;
    }

    /** The bank-coverable shortfall for one fact, or null when it passes already or cannot be granted. */
    private Long grantOf(Fact f, RouteContext facts, Inventory pack, Bank bank) {
        if (f.test(facts)) {
            return null;
        }
        return switch (f.kind()) {
            case CARRIED_AT_LEAST, WEARING -> {
                long needTo = f.kind() == Fact.Kind.WEARING ? 1 : f.threshold();
                long shortfall = needTo - pack.count(f.itemId());
                if (shortfall <= 0 || drawableStock(pack, bank, f.itemId()) < shortfall) {
                    yield null;
                }
                yield shortfall;
            }
            case CARRYING_MELEE_WEAPON -> {
                int weapon = MeleeWeapons.firstBanked(bank, serverData);
                yield weapon >= 0 ? 1L : null;
            }
            // One meal aboard lifts a Hits gate to the base level; below that no food helps.
            case SKILL_AT_LEAST ->
                f.foodLifts()
                                && facts.account().stats().baseLevel(f.skill()) >= f.threshold()
                                && Meals.bestBanked(bank, serverData) >= 0
                        ? 1L
                        : null;
            default -> null;
        };
    }

    private int grantItemOf(Fact f, Bank bank) {
        if (f.kind() == Fact.Kind.CARRYING_MELEE_WEAPON) {
            return MeleeWeapons.firstBanked(bank, serverData);
        }
        if (f.foodLifts()) {
            return Meals.bestBanked(bank, serverData);
        }
        return f.itemId();
    }

    private Candidate bankViaCandidate(
            PlanRequest request,
            Resolved r,
            DangerField danger,
            boolean[] canUse,
            int[] tCosts,
            Point from,
            Point goal,
            Settle settle) {
        if (bankNodes.length == 0) {
            return null;
        }
        // The leg on is priced under the class of the pack the stop leaves: banking a credential closes its gates.
        Klass outbound = klassFor(canUse, tCosts, danger);
        Klass settled = settledKlass(r, request.policy(), danger, settle.projected(), outbound);
        BankPair pair =
                bestBankPair(tileToBanks(from, outbound), banksToGoal(goal, settled), stopCosts(request.policy()));
        if (pair == null) {
            return null;
        }
        Candidate c = assemble(pair.weight(), pair.time(), request.account(), chain(pair.to()), chain(pair.on()));
        Inventory pack = request.account().inventory();
        if (c.ledger.firstOverdrawn(0, pastStop(c.bankChainIndex), pack) < 0) {
            Inventory atStop = projectPack(pack, Map.of(), c.ledger.before(c.bankChainIndex));
            Manifest stop = planStop(
                            request,
                            c,
                            c.bankChainIndex,
                            atStop,
                            request.account().bank(),
                            Map.of())
                    .manifest();
            c.stopManifest = stop == null || stop.isEmpty() ? null : stop;
        }
        return c;
    }

    /** A bank, the lightest leg to it and on from it, and the weight and time of both with its stop. */
    private record BankPair(int bank, Comp to, Comp on, long weight, long time) {}

    /**
     * The lightest of every bank's leg in, stop and leg on, the quicker on a tie; null when no bank has
     * both legs. The legs are index-aligned with {@code bankNodes}.
     */
    private static @Nullable BankPair bestBankPair(Comp[] to, Comp[] on, int[] stops) {
        BankPair best = null;
        for (int i = 0; i < to.length; i++) {
            if (to[i].cost == Long.MAX_VALUE || on[i].cost == Long.MAX_VALUE) {
                continue;
            }
            long weight = to[i].cost + stops[i] + on[i].cost;
            long time = to[i].time + stops[i] + on[i].time;
            if (best == null || weight < best.weight() || (weight == best.weight() && time < best.time())) {
                best = new BankPair(i, to[i], on[i], weight, time);
            }
        }
        return best;
    }

    /**
     * A candidate over {@code legs}, each starting at the bank the one before ends at: the first join is
     * its stop, the second its second stop.
     */
    @SafeVarargs
    private Candidate assemble(long cost, long time, AccountFacts account, List<int[]>... legs) {
        Candidate c = new Candidate();
        c.cost = (int) cost;
        c.time = time;
        c.chain.addAll(legs[0]);
        if (c.chain.size() == 1 && c.chain.getFirst()[1] >= 0) {
            // A leg in that is one cast landing on the bank: index 0 is the start tile, so the stop gets its own.
            c.chain.add(new int[] {c.chain.getFirst()[0], -1});
        }
        for (int l = 1; l < legs.length; l++) {
            if (l == 1) {
                c.bankChainIndex = c.chain.size() - 1;
            } else {
                c.bankChainIndex2 = c.chain.size() - 1;
            }
            appendLeg(c.chain, legs[l]);
        }
        seal(c, account);
        return c;
    }

    /**
     * The class of {@code projected}, the pack a stop leaves, derived from {@code parent}, the class of
     * the leg in: only the facts that read the pack are read again. An all-eligible probe bypasses the
     * gates here as on the leg in.
     */
    private Klass settledKlass(Resolved r, NavPolicy policy, DangerField danger, Inventory projected, Klass parent) {
        if (policy.assumeAllEligible()) {
            boolean[] all = allEligible(policy);
            return new Klass(this, all, costsFor(policy, all), danger, parent);
        }
        RouteContext settledFacts = r.facts.withAccount(r.facts.account().withInventory(projected));
        ClassEntry c = classOfFacts(packFactBits(settledFacts, r.factBits));
        if (!policy.costOverrides().isEmpty()) {
            boolean[] canUse2 = c.canUse().clone();
            return new Klass(this, canUse2, costsFor(policy, canUse2), danger, parent);
        }
        if (c.canUse() == parent.canUse) {
            return parent;
        }
        return new Klass(this, c.canUse(), baseCosts, danger, c.elig(), parent);
    }

    /**
     * Appends {@code leg}, which starts from the bank node {@code chain} ends at, dropping its head where
     * it repeats that node; a head that opens with a cast from that tile, or that is another node the
     * search seeded from it, is kept.
     */
    private static void appendLeg(List<int[]> chain, List<int[]> leg) {
        int[] head = leg.getFirst();
        boolean repeats = head[1] < 0 && head[0] == chain.getLast()[0];
        for (int i = repeats ? 1 : 0; i < leg.size(); i++) {
            chain.add(leg.get(i));
        }
    }

    private List<Integer> hopsOf(List<int[]> chain) {
        List<Integer> hops = new ArrayList<>();
        for (int[] link : chain) {
            if (link[1] >= 0) {
                hops.add(link[1]);
            }
        }
        return hops;
    }

    /** Whether every plan is checked against {@link #routeViolation}: under assertions, as in tests. */
    private static final boolean CHECKED = Engine.class.desiredAssertionStatus();

    /**
     * The first way {@code route} cannot be walked as it projects, or null: a hop whose gate its pack
     * fails (unless the policy lifts it), a fare its pack cannot pay, a stop that overfills the pack or
     * draws more than the bank holds by then, or an arrival the request's loadout refuses.
     */
    @Nullable
    String routeViolation(PlanRequest request, Route route) {
        AccountFacts account = request.account();
        NavPolicy policy = request.policy();
        Map<Integer, Long> drawn = new HashMap<>();
        for (Route.Step step : route.steps()) {
            Inventory pack = step.pack();
            if (step instanceof Route.Hop hop) {
                if (!policy.assumeAllEligible() && !hopOpens(hop, account, policy)) {
                    return hop.transport() + " is shut to its pack " + pack.slots();
                }
                for (Map.Entry<Integer, Long> fare : hop.fare().entrySet()) {
                    if (pack.count(NoteForm.UNNOTED, fare.getKey()) < fare.getValue()) {
                        return hop.transport() + " costs " + fare.getValue() + " of item " + fare.getKey()
                                + ", the pack holds " + pack.count(NoteForm.UNNOTED, fare.getKey());
                    }
                }
            } else if (step instanceof Route.BankStop stop) {
                Inventory leaving = stop.leaving();
                if (leaving.usedSlots() > leaving.capacity()) {
                    return "the stop at " + stop.bank() + " leaves " + leaving.usedSlots() + " slots used";
                }
                Set<Integer> ids = new LinkedHashSet<>(leaving.itemIds());
                ids.addAll(pack.itemIds());
                for (int id : ids) {
                    long net = drawn.merge(
                            id, leaving.count(NoteForm.BOTH, id) - pack.count(NoteForm.BOTH, id), Long::sum);
                    if (net > account.bank().count(id)) {
                        return "the stop at " + stop.bank() + " draws item " + id + " to " + net
                                + " past the start, the bank holds "
                                + account.bank().count(id);
                    }
                }
            } else if (step instanceof Route.Arrive arrive) {
                LoadoutSpec loadout = request.loadout().reachable(account.inventory(), account.bank(), serverData);
                if (!loadout.satisfiedBy(arrive.pack())) {
                    return "the arrival pack " + arrive.pack().slots() + " fails " + loadout;
                }
            }
        }
        return null;
    }

    private boolean hopOpens(Route.Hop hop, AccountFacts account, NavPolicy policy) {
        RouteContext ctx = new RouteContext(account.withInventory(hop.pack()), policy, serverData);
        for (int tag = 0; tag < names.length; tag++) {
            if (names[tag].equals(hop.transport()) && (lifted(policy, tag) || uniquePrereqs[prereqOf[tag]].test(ctx))) {
                return true;
            }
        }
        return false;
    }

    private Route buildRoute(PlanRequest request, Candidate c, Area goalArea) {
        List<Route.Step> steps = new ArrayList<>();
        Inventory pack = request.account().inventory();
        // The withdraw stop, and the deposit stop too, can be the start tile, which the loop below never
        // visits: both manifests, in order, at the one booth.
        boolean stop2AtStart = c.bankChainIndex2 == 0 && c.stopManifest2 != null;
        if (c.bankChainIndex == 0 && c.stopManifest != null) {
            Inventory leaving = Packs.apply(pack, c.stopManifest, serverData);
            if (stop2AtStart) {
                leaving = Packs.apply(leaving, c.stopManifest2, serverData);
            }
            addStop(steps, new Route.BankStop(nodePoint(c.chain.get(0)[0]), pack, leaving));
            pack = leaving;
        }
        // A cast from the start tile rides on the chain's root, which the loop below never visits either.
        int[] seed = c.chain.isEmpty() ? null : c.chain.get(0);
        if (seed != null && seed[1] >= 0) {
            int tag0 = seed[1];
            Map<Integer, Long> fare0 = fareOf(tag0, request.account());
            Point from0 = request.from();
            Set<Integer> wears0 = transports.get(tag0).wornGate(pack, serverData);
            pack = Packs.wear(pack, wears0, serverData);
            steps.add(new Route.Hop(
                    names[tag0], from0, landingTileOf(tag0, from0, nodePoint(seed[0])), fare0, wears0, pack));
            pack = Packs.consume(pack, fare0, serverData);
        }
        for (int i = 1; i < c.chain.size(); i++) {
            int[] link = c.chain.get(i);
            int node = link[0];
            int tag = link[1];
            boolean stop1 = c.bankChainIndex >= 0 && i == c.bankChainIndex && c.stopManifest != null;
            boolean stop2 = c.bankChainIndex2 >= 0 && i == c.bankChainIndex2 && c.stopManifest2 != null;
            if (tag < 0 && tag != TAG_BOUNDARY && !stop1 && !stop2) {
                continue;
            }
            if (tag >= 0) {
                Point hopEntry = entryTileOf(tag, nodePoint(prevNode(c, i)));
                Map<Integer, Long> fare = fareOf(tag, request.account());
                // The hop's pack wears the crossing's gate, so a step says what is on the body.
                Set<Integer> wears = transports.get(tag).wornGate(pack, serverData);
                pack = Packs.wear(pack, wears, serverData);
                steps.add(new Route.Hop(
                        names[tag],
                        hopEntry,
                        landingTileOf(tag, hopEntry, nodePoint(c.chain.get(i)[0])),
                        fare,
                        wears,
                        pack));
                pack = Packs.consume(pack, fare, serverData);
            } else if (tag == TAG_BOUNDARY) {
                // A seam crossing; a move inside a cluster is the hierarchy's scaffolding, not a waypoint.
                steps.add(new Route.Cross(spanOf(node), pack));
            }
            if (stop1 || stop2) {
                Inventory leaving = pack;
                if (stop1) {
                    leaving = Packs.apply(leaving, c.stopManifest, serverData);
                }
                if (stop2) {
                    leaving = Packs.apply(leaving, c.stopManifest2, serverData);
                }
                addStop(steps, new Route.BankStop(nodePoint(node), pack, leaving));
                pack = leaving;
            }
        }
        steps.add(new Route.Arrive(goalArea, pack));
        if (c.time == Long.MAX_VALUE || c.time >= Integer.MAX_VALUE) {
            throw new IllegalStateException("a route shape priced a weight with no duration: " + c.cost);
        }
        // The route reports its duration, never the weight it was chosen by.
        return new Route(List.copyOf(steps), (int) c.time, c.debits);
    }

    /**
     * The transport's real entry tile nearest {@code near}, where the chain's node can be a tile or two
     * off every entry. An entry-free transport keeps {@code near}.
     */
    private Point entryTileOf(int tag, Point near) {
        Transport t = transports.get(tag);
        if (t.entryFree() || t.entries().isEmpty()) {
            return near;
        }
        Point best = null;
        int bestD = Integer.MAX_VALUE;
        for (Point e : t.entries()) {
            int d = Math.max(Math.abs(e.x() - near.x()), Math.abs(e.y() - near.y()));
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    /**
     * The transport's real landing from {@code entry}, not the nearest node; {@code nodeFallback} for an
     * entry-free one.
     */
    private Point landingTileOf(int tag, Point entry, Point nodeFallback) {
        Transport t = transports.get(tag);
        if (!t.entryFree()) {
            Point d = t.destinationFrom(entry);
            if (d != null) {
                return d;
            }
        }
        return nodeFallback;
    }

    private Map<Integer, Long> fareOf(int tIdx, AccountFacts account) {
        Map<Integer, Long> fare = new LinkedHashMap<>();
        for (Transport.Consumed d : transports.get(tIdx).consumes(account)) {
            fare.merge(d.item().id(), d.count(), Long::sum);
        }
        return fare;
    }

    private int prevNode(Candidate c, int i) {
        return c.chain.get(i - 1)[0];
    }

    private Point nodePoint(int node) {
        return Point.of(graph.nodeX(node), graph.nodeY(node));
    }

    private Rect spanOf(int node) {
        int w = CollisionMap.WIDTH;
        int lo = graph.nodeSpanLo(node);
        int hi = graph.nodeSpanHi(node);
        return new Rect(
                Math.min(lo % w, hi % w), Math.min(lo / w, hi / w), Math.max(lo % w, hi % w), Math.max(lo / w, hi / w));
    }

    /** {@code pack} with {@code grants} in and {@code debits} out, a view whose slots agree with its counts. */
    private Inventory projectPack(Inventory pack, Map<Integer, Long> grants, Map<Integer, Long> debits) {
        return Packs.adjust(pack, grants, debits, serverData);
    }
}
