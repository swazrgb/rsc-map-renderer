package openrsc.nav.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;
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
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Planner;
import openrsc.nav.TravelTimes;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.cache.NavCacheBudget;

/**
 * Times travel pricing over a roster of real accounts: each solve prices every account, from where
 * it stands, against every site in one {@code estimateBatch}, and a share of the accounts then steps
 * one tile before the next solve.
 *
 * <p>Arguments: {@code DIR [warm] [solves]}, defaulting to 5 and 30. {@code DIR} holds
 * {@code roster.tsv} (username, status, x, z, skills as {@code NAME=level/base/xp;…}, pack as
 * {@code id:amount:equipped;…}, bank as {@code id:amount;…}, quests as {@code id:stage;…}) and
 * {@code sites.tsv} (x, z, role). Properties: {@code -Droster.move} is the share that steps (default
 * 0.37); {@code -Droster.prohibit=A,B} prohibits those transports; {@code -Droster.danger=off} turns
 * danger avoidance off; {@code -Droster.jfr=FILE} records the measured solves with JFR's profile
 * settings, the warm-up left out.
 *
 * <p>Uses only the public {@link Planner} API, so the same source runs against another engine's
 * jar; the printed checksum sums every answer, so two engines that price alike print the same one.
 */
public final class RosterSolve {

    private static final int CHECKSUM_NO_ROUTE = -1;

    record Account(String username, AccountFacts facts, int[] at) {}

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        int warm = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        int solves = args.length > 2 ? Integer.parseInt(args[2]) : 30;
        double move = Double.parseDouble(System.getProperty("roster.move", "0.37"));
        String prohibit = System.getProperty("roster.prohibit", "");
        NavPolicy policy = policyFor(prohibit);
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
            accounts.add(account(env, line.split("\t", -1)));
        }
        List<Dest> sites = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("sites.tsv"))) {
            String[] f = line.split("\t");
            sites.add(Point.of(Integer.parseInt(f[0]), Integer.parseInt(f[1])));
        }
        CollisionMap map = env.collisionMap();
        SplittableRandom rnd = new SplittableRandom(20260914L);
        long[] micros = new long[solves];
        long checksum = 0;
        long first = 0;
        String jfr = System.getProperty("roster.jfr");
        Recording recording = null;
        for (int s = 0; s < warm + solves; s++) {
            if (s == warm && jfr != null) {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.start();
            }
            long t0 = System.nanoTime();
            long sum = 0;
            for (Account a : accounts) {
                int x = a.at[0];
                int z = a.at[1];
                TravelTimes row = nav.estimateBatch(a.facts.at(x, z), Point.of(x, z), sites, policy);
                for (int i = 0; i < row.size(); i++) {
                    sum += row.get(i).deciOrElse(CHECKSUM_NO_ROUTE);
                }
            }
            long took = (System.nanoTime() - t0) / 1000;
            if (s == 0) {
                first = took;
            }
            if (s >= warm) {
                micros[s - warm] = took;
                checksum += sum;
            }
            step(accounts, map, rnd, move);
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
                "RosterSolve: %d accounts x %d sites, %d warm + %d solves, move=%.2f," + " prohibit=[%s], danger=%s%n",
                accounts.size(), sites.size(), warm, solves, move, prohibit, policy.danger());
        System.out.printf(
                "RosterSolve: first solve %.1fms; per solve mean %.1fms p50 %.1fms p90 %.1fms" + " min %.1fms%n",
                first / 1000.0,
                total / 1000.0 / solves,
                sorted[solves / 2] / 1000.0,
                sorted[(int) (solves * 0.9)] / 1000.0,
                sorted[0] / 1000.0);
        System.out.printf("RosterSolve: checksum %d%n", checksum);
        System.out.println("RosterSolve: " + nav.cacheStats());
    }

    /**
     * The default policy with every comma-separated name in {@code prohibit} priced prohibitive, and
     * danger avoidance off under {@code -Droster.danger=off}.
     */
    static NavPolicy policyFor(String prohibit) {
        NavPolicy policy = NavPolicy.DEFAULT;
        if (!prohibit.isBlank()) {
            Map<String, Integer> overrides = new HashMap<>();
            for (String name : prohibit.split(",")) {
                overrides.put(name.trim(), NavPolicy.PROHIBITIVE);
            }
            NavPolicy d = NavPolicy.DEFAULT;
            policy = new NavPolicy(
                    d.danger(),
                    d.teleportSpells(),
                    d.teleportJewellery(),
                    Map.copyOf(overrides),
                    d.assumeAllEligible(),
                    d.avoidSkulled(),
                    d.berth());
        }
        return "off".equals(System.getProperty("roster.danger"))
                ? policy.withDanger(NavPolicy.DangerAvoidance.OFF)
                : policy;
    }

    /** Steps each account, with probability {@code move}, one tile in a random cardinal direction. */
    static void step(List<Account> accounts, CollisionMap map, SplittableRandom rnd, double move) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (Account a : accounts) {
            if (rnd.nextDouble() >= move) {
                continue;
            }
            int[] d = dirs[rnd.nextInt(dirs.length)];
            int nx = a.at[0] + d[0];
            int nz = a.at[1] + d[1];
            if (map.inBounds(nx, nz) && !map.fullyBlocked(nx, nz)) {
                a.at[0] = nx;
                a.at[1] = nz;
            }
        }
    }

    /** One {@code roster.tsv} line, split on tabs; bank items with no {@link ItemId} are dropped. */
    static Account account(ServerData env, String[] f) {
        MutableStats stats = new MutableStats();
        for (String s : f[4].split(";")) {
            if (s.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            String[] v = s.substring(eq + 1).split("/");
            stats.setSkill(
                    Skill.valueOf(s.substring(0, eq)).ordinal(),
                    Integer.parseInt(v[0]),
                    Integer.parseInt(v[1]),
                    Integer.parseInt(v[2]));
        }
        MutableInventory inv = new MutableInventory();
        int slot = 0;
        for (String s : f[5].split(";")) {
            if (s.isEmpty() || slot >= 30) {
                continue;
            }
            String[] v = s.split(":");
            inv.setSlot(
                    slot, new InventorySlot(slot, Integer.parseInt(v[0]), Integer.parseInt(v[1]), "1".equals(v[2])));
            slot++;
        }
        MutableBank bank = new MutableBank();
        int b = 0;
        for (String s : f[6].split(";")) {
            if (s.isEmpty()) {
                continue;
            }
            String[] v = s.split(":");
            ItemId known = ItemId.getById(Integer.parseInt(v[0]));
            if (known != null) {
                bank.setSlot(b, new BankSlot(b, known, Integer.parseInt(v[1])));
                b++;
            }
        }
        MutableQuests quests = new MutableQuests();
        for (String s : f[7].split(";")) {
            if (s.isEmpty()) {
                continue;
            }
            String[] v = s.split(":");
            quests.setStage(Integer.parseInt(v[0]), Integer.parseInt(v[1]));
        }
        int x = Integer.parseInt(f[2]);
        int z = Integer.parseInt(f[3]);
        AccountFacts facts = new AccountFacts.Snapshot(
                env, inv.snapshot(), bank.snapshot(), stats.snapshot(), quests.snapshot(), Point.of(x, z));
        return new Account(f[0], facts, new int[] {x, z});
    }
}
