package openrsc.nav.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.Route;
import openrsc.nav.bench.RosterSolve.Account;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCacheBudget;

/**
 * Times route planning over the roster {@link RosterSolve} reads: each pass plans every account from
 * where it stands to one site (its roster index modulo the sites), and a share of the accounts then
 * steps one tile before the next pass.
 *
 * <p>Arguments: {@code DIR [warm] [passes]}, defaulting to 5 and 30, with the same files and
 * properties as {@link RosterSolve}. {@code -Droster.anyof=N} plans each site as an {@code AnyOf} of
 * it and up to N-1 standable neighbours. {@code -Droster.threads=account} plans each account on a
 * thread of its own, so per-thread engine state sees one account; a pass then reports the summed
 * plan time rather than wall time.
 *
 * <p>The roster holds no loadouts, so a third of the accounts carry anything, a third also want one
 * item they bank but do not carry (a withdrawal), and a third want only the first stack they carry
 * (a deposit of the rest). The checksum folds every route, so two engines that plan alike print the
 * same one.
 */
public final class RosterPlan {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int warm = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int passes = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        double move = Double.parseDouble(System.getProperty("roster.move", "0.37"));
        String prohibit = System.getProperty("roster.prohibit", "");
        NavPolicy policy = RosterSolve.policyFor(prohibit);
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.resolve(conf);
        ServerData env;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            env = ServerData.load(conf, world, landscape);
        }
        Pathfinder pf =
                Pathfinder.load(env, new CaffeineNavCaches(new NavCacheBudget(512L << 20, 128L << 20, 1536L << 20)));
        Planner nav = pf.nav();
        List<Account> accounts = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("roster.tsv"))) {
            accounts.add(RosterSolve.account(env, line.split("\t", -1)));
        }
        int anyOf = Integer.parseInt(System.getProperty("roster.anyof", "1"));
        List<Dest> sites = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("sites.tsv"))) {
            String[] f = line.split("\t");
            Point site = Point.of(Integer.parseInt(f[0]), Integer.parseInt(f[1]));
            if (anyOf <= 1) {
                sites.add(site);
                continue;
            }
            List<Area> options = new ArrayList<>(List.of(site));
            int[][] around = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
            for (int[] d : around) {
                int nx = site.x() + d[0];
                int nz = site.y() + d[1];
                if (options.size() < anyOf
                        && env.collisionMap().inBounds(nx, nz)
                        && !env.collisionMap().fullyBlocked(nx, nz)) {
                    options.add(Point.of(nx, nz));
                }
            }
            sites.add(options.size() == 1 ? site : new Dest.AnyOf(options));
        }
        LoadoutSpec[] loadouts = new LoadoutSpec[accounts.size()];
        for (int i = 0; i < loadouts.length; i++) {
            loadouts[i] = loadoutFor(i, accounts.get(i).facts());
        }
        ExecutorService[] loops = null;
        if ("account".equals(System.getProperty("roster.threads"))) {
            loops = new ExecutorService[accounts.size()];
            for (int i = 0; i < loops.length; i++) {
                loops[i] = Executors.newSingleThreadExecutor(
                        Thread.ofPlatform().daemon().factory());
            }
        }
        SplittableRandom rnd = new SplittableRandom(20260914L);
        long[] micros = new long[passes];
        long checksum = 0;
        long first = 0;
        int[] shapes = new int[4];
        String jfr = System.getProperty("roster.jfr");
        Recording recording = null;
        for (int s = 0; s < warm + passes; s++) {
            if (s == warm && jfr != null) {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.start();
            }
            long t0 = System.nanoTime();
            long planNanos = 0;
            long sum = 0;
            int[] passShapes = new int[4];
            for (int i = 0; i < accounts.size(); i++) {
                Account a = accounts.get(i);
                Point at = Point.of(a.at()[0], a.at()[1]);
                PlanRequest request = new PlanRequest(
                        a.facts().at(at.x(), at.y()), at, sites.get(i % sites.size()), loadouts[i], policy);
                Asked asked = loops == null
                        ? ask(nav, request)
                        : loops[i].submit(() -> ask(nav, request)).get();
                planNanos += asked.nanos();
                sum = 31 * sum + fingerprint(asked.result(), passShapes);
            }
            long took = (loops == null ? System.nanoTime() - t0 : planNanos) / 1000;
            if (s == 0) {
                first = took;
            }
            if (s >= warm) {
                micros[s - warm] = took;
                checksum += sum;
                for (int k = 0; k < shapes.length; k++) {
                    shapes[k] += passShapes[k];
                }
            }
            RosterSolve.step(accounts, env.collisionMap(), rnd, move);
        }
        if (recording != null) {
            recording.stop();
            recording.dump(Path.of(jfr));
            recording.close();
        }
        long[] sorted = micros.clone();
        Arrays.sort(sorted);
        long total = 0;
        for (long m : micros) {
            total += m;
        }
        System.out.printf(
                "RosterPlan: %d accounts, %d sites, %d warm + %d passes, move=%.2f,"
                        + " prohibit=[%s], danger=%s, anyof=%d, threads=%s%n",
                accounts.size(),
                sites.size(),
                warm,
                passes,
                move,
                prohibit,
                policy.danger(),
                anyOf,
                loops == null ? "one (wall time)" : "one per account (summed plan time)");
        System.out.printf(
                "RosterPlan: routes over the passes: %d none, %d arrive-only, %d without a"
                        + " stop, %d with a bank stop%n",
                shapes[0], shapes[1], shapes[2], shapes[3]);
        System.out.printf(
                "RosterPlan: first pass %.1fms; per pass mean %.1fms p50 %.1fms p90 %.1fms" + " min %.1fms%n",
                first / 1000.0,
                total / 1000.0 / passes,
                sorted[passes / 2] / 1000.0,
                sorted[(int) (passes * 0.9)] / 1000.0,
                sorted[0] / 1000.0);
        System.out.printf("RosterPlan: checksum %d%n", checksum);
        System.out.println("RosterPlan: " + nav.cacheStats());
    }

    private record Asked(PlanResult result, long nanos) {}

    private static Asked ask(Planner nav, PlanRequest request) {
        long t0 = System.nanoTime();
        PlanResult result = nav.plan(request);
        return new Asked(result, System.nanoTime() - t0);
    }

    private static LoadoutSpec loadoutFor(int i, AccountFacts facts) {
        return switch (i % 3) {
            case 1 -> {
                for (BankSlot b : facts.bank().slots()) {
                    if (b != null
                            && b.item() != null
                            && b.amount() > 0
                            && facts.inventory().count(b.item().id()) == 0) {
                        yield new LoadoutSpec(List.of(new LoadoutSpec.Item(b.item(), 1, 1, 1, false)), false, true);
                    }
                }
                yield LoadoutSpec.any();
            }
            case 2 -> {
                for (InventorySlot s : facts.inventory().slots()) {
                    ItemId known = s == null ? null : s.item();
                    if (known != null) {
                        long held = facts.inventory().count(known.id());
                        yield new LoadoutSpec(List.of(new LoadoutSpec.Item(known, 0, held, held, false)));
                    }
                }
                yield LoadoutSpec.any();
            }
            default -> LoadoutSpec.any();
        };
    }

    /**
     * A hash of everything about the route: steps, places, fares, manifests, packs, duration and
     * debits. Maps and sets are read sorted, since an immutable one's iteration order changes from
     * one JVM to the next. Counts the route's shape into {@code shapes}: none, arrive-only, no bank
     * stop, a bank stop.
     */
    private static long fingerprint(PlanResult r, int[] shapes) {
        if (!(r instanceof PlanResult.Planned(Route route))) {
            shapes[0]++;
            return 7;
        }
        boolean stop = false;
        StringBuilder sb =
                new StringBuilder().append(route.totalCostDeci()).append('|').append(new TreeMap<>(route.debits()));
        for (Route.Step step : route.steps()) {
            sb.append('|');
            switch (step) {
                case Route.Cross c -> sb.append("C").append(c.span());
                case Route.Hop h ->
                    sb.append("H")
                            .append(h.transport())
                            .append(h.entry())
                            .append(h.landing())
                            .append(new TreeMap<>(h.fare()));
                case Route.BankStop b -> {
                    stop = true;
                    sb.append("B")
                            .append(b.bank())
                            .append(new TreeMap<>(b.manifest().withdraw()))
                            .append(new TreeMap<>(b.manifest().deposit()))
                            .append(new TreeSet<>(b.manifest().equip()));
                }
                case Route.Arrive a -> sb.append("A").append(a.target());
            }
            TreeMap<Integer, Long> pack = new TreeMap<>();
            for (var s : step.pack().slots()) {
                pack.merge(s.item().id(), (long) s.amount(), Long::sum);
            }
            sb.append(pack);
        }
        shapes[route.steps().size() == 1 ? 1 : stop ? 3 : 2]++;
        return sb.toString().hashCode();
    }

    private RosterPlan() {}
}
