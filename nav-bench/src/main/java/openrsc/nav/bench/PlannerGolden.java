package openrsc.nav.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.container.NoteForm;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.Route;
import openrsc.nav.Transport;
import openrsc.nav.TravelTime;
import openrsc.nav.TravelTimes;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCacheBudget;
import openrsc.nav.eligibility.MeleeWeapons;
import openrsc.nav.eligibility.RouteContext;
import openrsc.nav.probe.NavProbe;
import openrsc.nav.transports.Banks;

/**
 * A planner's answers to a fixed, seeded battery of questions, written as one line each, so that two
 * engines behind {@link Planner} can be held against each other through the public API alone.
 *
 * <p>The battery asks from random walkable tiles anywhere on the map, for the all-eligible probe,
 * accounts levelled to 1, 20, 45 and 70, and {@code -Dgolden.accounts} (default 40) random accounts
 * with random levels, quest stages, carried and banked gate items and danger avoidance. It asks
 * estimates, batches, and plans to a tile, an area, a bank or an {@code AnyOf}, some with a loadout
 * that needs a banked item.
 *
 * <p>Arguments: {@code record FILE [SEED]} writes the answers; {@code check FILE [SEED]} compares
 * them and exits 1 on a mismatch. A plan with the same duration but other steps counts as a tie, not
 * a mismatch: which of two equal routes a search settles on is not part of the contract.
 * {@code triage} explains every refused plan and {@code audit} checks every planned one; both print
 * a tally, and audit exits 1 on any faulty plan.
 */
public final class PlannerGolden {

    private static final String SEP = " => ";

    private static final int GOLDEN_NO_ROUTE = -1;

    /** A batch as a golden file writes it: deci-ticks, {@link #GOLDEN_NO_ROUTE} for no route. */
    private static int[] golden(TravelTimes times) {
        int[] out = new int[times.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = times.get(i).deciOrElse(GOLDEN_NO_ROUTE);
        }
        return out;
    }

    private static boolean triageMode;
    private static boolean auditMode;

    private static int auditPlanned;
    private static int auditFaulty;
    private static final List<String> FAULTS = new ArrayList<>();

    /**
     * Runs the battery in audit mode against {@code pf}: every planned route is checked against what a
     * walker following it would meet (see {@code Battery.audit}). Returns one entry per faulty plan,
     * empty when every plan holds up. Not thread-safe: the modes are static.
     */
    public static List<String> auditBattery(ServerData env, Pathfinder pf, long seed, int randomAccounts) {
        auditMode = true;
        auditPlanned = 0;
        auditFaulty = 0;
        FAULTS.clear();
        TALLY.clear();
        try {
            new Battery(env, pf, seed, randomAccounts).run();
            return List.copyOf(FAULTS);
        } finally {
            auditMode = false;
        }
    }

    private static final Map<String, Integer> TALLY = new TreeMap<>();

    private static void tally(String key) {
        TALLY.merge(key, 1, Integer::sum);
    }

    public static void main(String[] args) throws Exception {
        String mode = args[0];
        triageMode = "triage".equals(mode);
        auditMode = "audit".equals(mode);
        Path file = args.length > 1 ? Path.of(args[1]) : null;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 20260914L;
        int randomAccounts = Integer.getInteger("golden.accounts", 40);
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        ServerData env;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            env = ServerData.load(conf, world, landscape);
        }
        Pathfinder pf = Pathfinder.load(env, new CaffeineNavCaches(NavCacheBudget.DEFAULTS));
        long t0 = System.nanoTime();
        List<String> lines = new Battery(env, pf, seed, randomAccounts).run();
        System.out.printf("PlannerGolden: %d answers in %dms%n", lines.size(), (System.nanoTime() - t0) / 1_000_000);
        if (triageMode || auditMode) {
            System.out.println();
            if (auditMode) {
                System.out.printf("AUDIT: %d planned answers, %d with faults%n", auditPlanned, auditFaulty);
            }
            System.out.println("TALLY");
            TALLY.forEach((k, n) -> System.out.printf("  %4d  %s%n", n, k));
            if (auditFaulty > 0) {
                System.exit(1);
            }
            return;
        }
        if ("record".equals(mode)) {
            Files.write(file, lines);
            return;
        }
        List<String> expected = Files.readAllLines(file);
        if (expected.size() != lines.size()) {
            throw new IllegalStateException(
                    "battery changed shape: " + expected.size() + " recorded, " + lines.size() + " asked");
        }
        int same = 0;
        int ties = 0;
        int wrong = 0;
        for (int i = 0; i < lines.size(); i++) {
            String want = expected.get(i);
            String got = lines.get(i);
            if (want.equals(got)) {
                same++;
                continue;
            }
            String query = want.substring(0, want.indexOf(SEP));
            if (!got.startsWith(query + SEP)) {
                throw new IllegalStateException("battery changed at line " + i + ": " + query);
            }
            if (want.startsWith("P") && durationOf(want).equals(durationOf(got))) {
                if (ties++ < 15) {
                    System.out.printf("TIE   %s%n  want %s%n  got  %s%n", query, answer(want), answer(got));
                }
            } else if (wrong++ < 40) {
                System.out.printf("WRONG %s%n  want %s%n  got  %s%n", query, answer(want), answer(got));
            }
        }
        System.out.printf("PlannerGolden: %d same, %d tied plans with other steps, %d wrong%n", same, ties, wrong);
        if (wrong > 0) {
            System.exit(1);
        }
    }

    private static String answer(String line) {
        return line.substring(line.indexOf(SEP) + SEP.length());
    }

    private static String durationOf(String line) {
        String a = answer(line);
        int sp = a.indexOf(' ');
        return sp < 0 ? a : a.substring(0, sp);
    }

    private static final class Battery {
        final ServerData env;
        final Pathfinder pf;
        final Planner nav;
        final CollisionMap map;
        final SplittableRandom rnd;
        final int randomAccounts;
        final int[] gateItems;
        final int[] gateQuests;
        final List<String> out = new ArrayList<>();

        Battery(ServerData env, Pathfinder pf, long seed, int randomAccounts) {
            this.env = env;
            this.pf = pf;
            this.nav = pf.nav();
            this.map = env.collisionMap();
            this.rnd = new SplittableRandom(seed);
            this.randomAccounts = randomAccounts;
            this.gateItems = pf.eligibilityItemIds();
            this.gateQuests = pf.eligibilityQuestIds();
        }

        List<String> run() {
            ask("probe", null, NavPolicy.DEFAULT);
            ask("lvl1", leveled(1), NavPolicy.DEFAULT);
            ask("lvl20", leveled(20), NavPolicy.DEFAULT);
            ask("lvl45", leveled(45), NavPolicy.DEFAULT);
            ask("lvl70-dangerOff", leveled(70), NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF));
            for (int i = 0; i < randomAccounts; i++) {
                AccountFacts account = randomAccount();
                NavPolicy policy = rnd.nextInt(4) == 0
                        ? NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF)
                        : NavPolicy.DEFAULT;
                ask("rand" + i, account, policy);
            }
            return out;
        }

        private void ask(String name, AccountFacts account, NavPolicy base) {
            NavPolicy policy = NavProbe.policyFor(account, base);
            for (int i = 0; i < 40; i++) {
                Point from = tile();
                Point to = tile();
                AccountFacts facts = NavProbe.factsFor(account, pf, from.x(), from.y());
                out.add("E " + name + " " + from + " " + to + SEP
                        + nav.estimate(facts, from, to, policy).deciOrElse(GOLDEN_NO_ROUTE));
            }
            for (int i = 0; i < 5; i++) {
                Point from = tile();
                List<Dest> dests = new ArrayList<>();
                for (int k = 0; k < 12; k++) {
                    dests.add(dest());
                }
                AccountFacts facts = NavProbe.factsFor(account, pf, from.x(), from.y());
                out.add("B " + name + " " + from + " " + dests + SEP
                        + Arrays.toString(golden(nav.estimateBatch(facts, from, dests, policy))));
            }
            for (int i = 0; i < 18; i++) {
                Point from = tile();
                Dest to = dest();
                LoadoutSpec loadout = i < 12 ? LoadoutSpec.any() : bankedItemLoadout(account);
                AccountFacts facts = NavProbe.factsFor(account, pf, from.x(), from.y());
                PlanResult r = nav.plan(new PlanRequest(facts, from, to, loadout, policy));
                out.add("P " + name + " " + from + " " + to + " " + loadout + SEP + summary(r));
                if (triageMode && !(r instanceof PlanResult.Planned)) {
                    triage(name, policy, facts, from, to, loadout);
                }
                if (auditMode && r instanceof PlanResult.Planned p) {
                    audit(name, policy, facts, from, to, loadout, p.route());
                }
            }
        }

        private int[] compSize;

        private int[] inbound;
        private int[] outbound;
        private int[] sampleTile;
        private Map<String, Transport> byName;

        /**
         * Builds, once, the static walking components, their sizes, and how many transports lead into
         * and out of each: what {@link #explain} reads a geography refusal off.
         */
        private void topology() {
            if (compSize != null) {
                return;
            }
            int n = map.componentCount() + 1;
            compSize = new int[n];
            inbound = new int[n];
            outbound = new int[n];
            sampleTile = new int[n];
            for (int y = 0; y < CollisionMap.HEIGHT; y++) {
                for (int x = 0; x < CollisionMap.WIDTH; x++) {
                    int c = map.componentId(x, y);
                    if (c > 0) {
                        if (compSize[c]++ == 0) {
                            sampleTile[c] = y * CollisionMap.WIDTH + x;
                        }
                    }
                }
            }
            byName = new HashMap<>();
            for (Transport t : pf.transports()) {
                byName.putIfAbsent(t.name(), t);
                for (Point e : t.entries()) {
                    Point land;
                    try {
                        land = t.destinationFrom(e);
                    } catch (RuntimeException ex) {
                        continue;
                    }
                    int cl = land == null ? 0 : map.componentId(land.x(), land.y());
                    if (cl == 0) {
                        continue;
                    }
                    // An entry is often the blocked object itself, fired from a neighbouring tile.
                    TreeSet<Integer> sources = new TreeSet<>();
                    addComp(sources, e.x(), e.y());
                    if (sources.isEmpty()) {
                        addComp(sources, e.x() + 1, e.y());
                        addComp(sources, e.x() - 1, e.y());
                        addComp(sources, e.x(), e.y() + 1);
                        addComp(sources, e.x(), e.y() - 1);
                    }
                    sources.remove(cl);
                    if (!sources.isEmpty()) {
                        inbound[cl]++;
                        for (int c : sources) {
                            outbound[c]++;
                        }
                    }
                }
            }
        }

        private void addComp(TreeSet<Integer> out, int x, int y) {
            int c = map.componentId(x, y);
            if (c > 0) {
                out.add(c);
            }
        }

        private TreeSet<Integer> comps(Dest d) {
            TreeSet<Integer> out = new TreeSet<>();
            List<Area> areas =
                    d instanceof Dest.AnyOf any ? any.options() : d instanceof Area a ? List.of(a) : List.of();
            for (Area a : areas) {
                if (a instanceof Point p) {
                    addComp(out, p.x(), p.y());
                } else if (a instanceof Rect r) {
                    for (int y = r.minY(); y <= r.maxY(); y++) {
                        for (int x = r.minX(); x <= r.maxX(); x++) {
                            addComp(out, x, y);
                        }
                    }
                }
            }
            return out;
        }

        private String comp(int c) {
            int t = sampleTile[c];
            return "#" + c + "(" + compSize[c] + " tiles near " + (t % CollisionMap.WIDTH) + ","
                    + (t / CollisionMap.WIDTH) + ", " + inbound[c] + " in, " + outbound[c] + " out)";
        }

        /**
         * Prints the concrete reason behind a geography or eligibility refusal and tallies it.
         *
         * <p>Geography: a destination no transport leads into is a pocket (64 tiles or fewer) or an
         * unreached region; a start no transport leads out of, the same from the other end; anything
         * else is a gap in the transport graph. Eligibility: the hops on the all-eligible route
         * ({@code open}) this account fails, with those its bank alone would open flagged as a bug.
         */
        private void explain(String cause, AccountFacts facts, NavPolicy policy, Point from, Dest to, PlanResult open) {
            topology();
            if (cause.equals("geography")) {
                int fc = map.componentId(from.x(), from.y());
                TreeSet<Integer> dc = comps(to);
                String why;
                if (dc.isEmpty()) {
                    why = "dest has no standable tile";
                } else if (dc.contains(fc)) {
                    why = "graph-gap (start and dest share a component, yet no walk)";
                } else if (dc.stream().noneMatch(c -> inbound[c] > 0)) {
                    why = dc.stream().allMatch(c -> compSize[c] <= 64) ? "dest-pocket" : "dest-unreached";
                } else if (outbound[fc] == 0) {
                    why = compSize[fc] <= 64 ? "start-pocket" : "start-stranded";
                } else {
                    why = "graph-gap";
                }
                tally("geography: " + why);
                if (why.startsWith("start")) {
                    tally("  region no transport leaves: " + comp(fc));
                } else if (why.startsWith("dest")) {
                    for (int c : dc) {
                        tally("  region no transport enters: " + comp(c));
                    }
                }
                StringBuilder sb = new StringBuilder();
                for (int c : dc) {
                    sb.append(comp(c)).append(' ');
                }
                System.out.println("       geography: " + why + "   start " + comp(fc) + "   dest "
                        + sb.toString().trim());
            } else if (cause.equals("eligibility") && open instanceof PlanResult.Planned p) {
                RouteContext ctx = new RouteContext(facts, policy, env);
                TreeSet<String> blocking = new TreeSet<>();
                TreeSet<String> bankable = new TreeSet<>();
                for (Route.Step s : p.route().steps()) {
                    if (!(s instanceof Route.Hop h)) {
                        continue;
                    }
                    Transport t = byName.get(h.transport());
                    if (t == null || t.prereq().test(ctx)) {
                        continue;
                    }
                    String label = t.prereq().toString();
                    if (t.prereq().testBanked(ctx)) {
                        bankable.add(h.transport() + " {" + label + "}");
                    } else {
                        blocking.add(label);
                    }
                }
                for (String b : blocking) {
                    tally("eligibility gate: " + b);
                }
                for (String b : bankable) {
                    tally((blocking.isEmpty() ? "GRANT BUG (bank alone opens it): " : "bankable, but blocked anyway: ")
                            + b);
                }
                System.out.println("       eligibility: lacks " + blocking
                        + (bankable.isEmpty() ? "" : "   BANKABLE-BUT-REFUSED " + bankable));
            }
        }

        /**
         * Prints why one plan was refused, as the first cause that, removed, lets it plan: the
         * loadout's shape, danger avoidance, this account's eligibility, else geography. When the
         * estimate still prices the trip, also prints what the account holds and banks. Draws nothing
         * from {@code rnd}, so the battery asks the same questions with triage on or off.
         */
        private void triage(
                String name, NavPolicy policy, AccountFacts facts, Point from, Dest to, LoadoutSpec loadout) {
            TravelTime est = nav.estimateBatch(facts, from, List.of(to), policy).get(0);
            boolean anyLoadout =
                    nav.plan(new PlanRequest(facts, from, to, LoadoutSpec.any(), policy)) instanceof PlanResult.Planned;
            boolean dangerOff = nav.plan(
                            new PlanRequest(facts, from, to, loadout, policy.withDanger(NavPolicy.DangerAvoidance.OFF)))
                    instanceof PlanResult.Planned;
            AccountFacts allEligible = NavProbe.factsFor(null, pf, from.x(), from.y());
            PlanResult open = nav.plan(new PlanRequest(
                    allEligible, from, to, LoadoutSpec.any(), NavProbe.policyFor(null, NavPolicy.DEFAULT)));
            boolean anyone = open instanceof PlanResult.Planned;
            String cause = anyLoadout
                    ? "loadout-shape"
                    : dangerOff ? "danger-avoidance" : anyone ? "eligibility" : "geography";
            boolean diverges = est.isReachable();
            System.out.printf(
                    "TRIAGE %-16s %-16s diverges=%-5b est=%-9d anyLoadout=%-5b dangerOff=%-5b "
                            + "anyone=%-5b %s -> %s %s%n",
                    cause,
                    name,
                    diverges,
                    est.deciOrElse(GOLDEN_NO_ROUTE),
                    anyLoadout,
                    dangerOff,
                    anyone,
                    from,
                    to,
                    loadout);
            explain(cause, facts, policy, from, to, open);
            if (!diverges) {
                return;
            }
            AccountFacts dryBank = new AccountFacts.Snapshot(
                    env, facts.inventory(), new MutableBank().snapshot(), facts.stats(), facts.quests(), facts.pos());
            TravelTime dry =
                    nav.estimateBatch(dryBank, from, List.of(to), policy).get(0);
            System.out.println("       estimate with an EMPTY bank: " + dry.deciOrElse(GOLDEN_NO_ROUTE)
                    + (!dry.isReachable()
                            ? "   <- the price was a bank detour the plan cannot make"
                            : "   <- still priced"));
            StringBuilder held = new StringBuilder();
            for (var s : facts.inventory().slots()) {
                held.append(s.item()).append('x').append(s.amount()).append(' ');
            }
            StringBuilder banked = new StringBuilder();
            for (var s : facts.bank().slots()) {
                banked.append(s.item()).append('x').append(s.amount()).append(' ');
            }
            System.out.println("       pack: "
                    + (held.isEmpty() ? "(empty)" : held.toString().trim()));
            System.out.println("       bank: "
                    + (banked.isEmpty() ? "(empty)" : banked.toString().trim()));
            int blade = MeleeWeapons.firstBanked(facts.bank(), env);
            if (blade >= 0) {
                MutableInventory armedPack = new MutableInventory();
                int i = 0;
                for (var s : facts.inventory().slots()) {
                    armedPack.setSlot(i, new InventorySlot(i, s.item().id(), s.amount(), s.equipped()));
                    i++;
                }
                armedPack.setSlot(i, new InventorySlot(i, blade, 1, false));
                AccountFacts armed = facts.withInventory(armedPack.snapshot());
                boolean plansArmed =
                        nav.plan(new PlanRequest(armed, from, to, loadout, policy)) instanceof PlanResult.Planned;
                System.out.println("       bank holds melee weapon id=" + blade
                        + "; plans with it IN THE PACK: " + plansArmed
                        + (plansArmed ? "   <- gates are satisfiable; the GRANT path is what fails" : ""));
            }
            if (open instanceof PlanResult.Planned p) {
                List<String> hops = new ArrayList<>();
                for (Route.Step s : p.route().steps()) {
                    if (s instanceof Route.Hop h) {
                        hops.add(h.transport());
                    }
                }
                System.out.println("       all-eligible way in: " + hops);
            }
        }

        /**
         * Checks one planned route against what a walker following it would meet: each hop's gate open
         * to the pack it is walked with, each fare covered by that pack, no step over capacity, no
         * withdrawal past what the account owns, and the arrival pack accepted by the loadout (as
         * lowered to what the account can reach).
         */
        private void audit(
                String name,
                NavPolicy policy,
                AccountFacts facts,
                Point from,
                Dest to,
                LoadoutSpec loadout,
                Route route) {
            topology();
            auditPlanned++;
            RouteContext ctx = new RouteContext(facts, policy, env);
            List<String> faults = new ArrayList<>();
            for (Route.Step step : route.steps()) {
                if (step.pack().usedSlots() > openrsc.bot.api.container.Inventory.CAPACITY) {
                    faults.add("over capacity: " + packOf(step));
                }
                switch (step) {
                    case Route.Hop h -> {
                        Transport t = byName.get(h.transport());
                        if (t == null) {
                            faults.add("unknown transport: " + h.transport());
                        } else if (!policy.assumeAllEligible() // the probe passes every gate by fiat
                                && !t.prereq().test(ctx.withAccount(facts.withInventory(h.pack())))) {
                            faults.add("gate closed: " + h.transport() + " {" + t.prereq() + "} with " + packOf(h));
                        }
                        h.fare().forEach((id, n) -> {
                            if (h.pack().count(id) < n) {
                                faults.add("fare short: " + h.transport() + " wants " + n + " of " + id + ", holds "
                                        + h.pack().count(id));
                            }
                        });
                    }
                    case Route.BankStop b ->
                        b.manifest().withdraw().forEach((item, target) -> {
                            long owned = b.pack().count(NoteForm.BOTH, item.id())
                                    + facts.bank().count(item.id());
                            if (target > owned) {
                                faults.add("withdraws beyond stock: " + item + " to " + target + " of " + owned);
                            }
                        });
                    case Route.Arrive a -> {
                        LoadoutSpec asked = loadout.reachable(facts.inventory(), facts.bank(), env);
                        if (!asked.satisfiedBy(a.pack())) {
                            faults.add((asked.minsMetBy(a.pack()) ? "arrival surplus: " : "ARRIVAL SHORT: ") + packOf(a)
                                    + " under " + asked);
                        }
                    }
                    default -> {}
                }
            }
            if (faults.isEmpty()) {
                return;
            }
            auditFaulty++;
            String header = "AUDIT " + name + " " + from + " -> " + to + " " + loadout;
            System.out.println(header);
            for (String f : faults) {
                System.out.println("       " + f);
                tally(f.substring(0, f.indexOf(':')));
            }
            FAULTS.add(header
                    + String.join("", faults.stream().map(f -> "\n       " + f).toList()));
        }

        /**
         * The route with every map and set sorted, so two runs print the same line: an immutable map
         * iterates in an order that changes from one JVM to the next.
         */
        private static String summary(PlanResult r) {
            if (!(r instanceof PlanResult.Planned p)) {
                return "NoRoute";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(p.route().totalCostDeci())
                    .append(" debits=")
                    .append(new TreeMap<>(p.route().debits()))
                    .append(" steps=[");
            for (Route.Step step : p.route().steps()) {
                switch (step) {
                    case Route.Cross c -> sb.append("Cross ").append(c.span());
                    case Route.Hop h ->
                        sb.append("Hop ")
                                .append(h.transport())
                                .append(' ')
                                .append(h.entry())
                                .append("->")
                                .append(h.landing())
                                .append(" fare=")
                                .append(new TreeMap<>(h.fare()));
                    case Route.BankStop b ->
                        sb.append("Bank ")
                                .append(b.bank())
                                .append(" withdraw=")
                                .append(new TreeMap<>(b.manifest().withdraw()))
                                .append(" deposit=")
                                .append(new TreeMap<>(b.manifest().deposit()))
                                .append(" equip=")
                                .append(new TreeSet<>(b.manifest().equip()));
                    case Route.Arrive a -> sb.append("Arrive ").append(a.target());
                }
                sb.append(' ').append(packOf(step)).append("; ");
            }
            return sb.append(']').toString();
        }

        /** The step's pack as totals per item id and the worn ids, both sorted, for the same reason. */
        private static String packOf(Route.Step step) {
            TreeMap<Integer, Long> totals = new TreeMap<>();
            TreeSet<Integer> worn = new TreeSet<>();
            for (var s : step.pack().slots()) {
                totals.merge(s.item().id(), (long) s.amount(), Long::sum);
                if (s.equipped()) {
                    worn.add(s.item().id());
                }
            }
            return "pack=" + totals + " worn=" + worn;
        }

        /** One of the account's banked items, carried; any loadout when it banks nothing. */
        private LoadoutSpec bankedItemLoadout(AccountFacts account) {
            if (account == null || account.bank().slots().isEmpty()) {
                return LoadoutSpec.any();
            }
            List<BankSlot> slots = account.bank().slots();
            BankSlot s = slots.get(rnd.nextInt(slots.size()));
            return LoadoutSpec.of(new LoadoutSpec.Item(s.item(), 1, 1, Long.MAX_VALUE, false));
        }

        private Dest dest() {
            int kind = rnd.nextInt(6);
            return switch (kind) {
                case 0 -> {
                    yield Banks.tiles(env).get(rnd.nextInt(Banks.tiles(env).size()));
                }
                case 1 -> {
                    yield area();
                }
                case 2 -> {
                    List<Area> options = new ArrayList<>();
                    int n = 2 + rnd.nextInt(2);
                    for (int i = 0; i < n; i++) {
                        options.add(rnd.nextBoolean() ? area() : tile());
                    }
                    yield new Dest.AnyOf(options);
                }
                default -> {
                    yield tile();
                }
            };
        }

        private Rect area() {
            Point c = tile();
            int r = rnd.nextInt(3);
            return new Rect(Math.max(0, c.x() - r), Math.max(0, c.y() - r), c.x() + r, c.y() + r);
        }

        private Point tile() {
            while (true) {
                int x = rnd.nextInt(CollisionMap.WIDTH);
                int floors = rnd.nextInt(10) < 7 ? 1 : CollisionMap.FLOOR_COUNT;
                int y = rnd.nextInt(CollisionMap.FLOOR_HEIGHT * floors);
                if (map.inBounds(x, y) && !map.fullyBlocked(x, y)) {
                    return Point.of(x, y);
                }
            }
        }

        private AccountFacts leveled(int level) {
            MutableStats stats = new MutableStats();
            for (Skill sk : Skill.values()) {
                stats.setSkill(sk.ordinal(), level, level, 0);
            }
            return new AccountFacts.Snapshot(
                    env,
                    new MutableInventory().snapshot(),
                    new MutableBank().snapshot(),
                    stats.snapshot(),
                    new MutableQuests().snapshot(),
                    Point.of(126, 670));
        }

        private AccountFacts randomAccount() {
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
            if (rnd.nextBoolean()) {
                inv.setSlot(slot, new InventorySlot(slot, ItemId.COINS.id(), rnd.nextInt(120), false));
                slot++;
            }
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
                    env, inv.snapshot(), bank.snapshot(), stats.snapshot(), quests.snapshot(), tile());
        }
    }
}
