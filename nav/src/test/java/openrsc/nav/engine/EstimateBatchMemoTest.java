package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.TravelTimes;
import openrsc.nav.cache.CaffeineNavCaches;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

@Isolated("one of nav's heaviest classes (0.3-0.5 GB live): alone, so its peak shares no heap")
class EstimateBatchMemoTest {

    @Test
    void aRepeatedBatchIsAHitAndEveryAnswerMatchesAnEngineThatKeepsNothing() {
        ServerData env = RealDefs.serverData();
        Pathfinder cachedPf = Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET));
        Engine cached = (Engine) cachedPf.nav();
        Engine uncached = (Engine) RealDefs.pathfinder().nav();
        List<Dest> sites = List.of(
                Point.of(122, 647),
                Point.of(310, 545),
                Point.of(220, 640),
                Point.of(126, 670),
                Point.of(651, 848),
                Point.of(120, 504),
                new Dest.AnyOf(List.of(Point.of(311, 546), Point.of(309, 544))));
        int[] gateItems = cachedPf.eligibilityItemIds();
        int[] gateQuests = cachedPf.eligibilityQuestIds();
        SplittableRandom rnd = new SplittableRandom(7);
        List<String> mismatches = new ArrayList<>();
        long repeats = 0;
        long hitsBefore = cached.batchRowHits();
        for (int a = 0; a < 24; a++) {
            Point from = Point.of(100 + rnd.nextInt(600), 450 + rnd.nextInt(400));
            AccountFacts account =
                    PrereqProgramTest.account(env, rnd, gateItems, gateQuests).at(from.x(), from.y());
            TravelTimes expected = uncached.estimateBatch(account, from, sites, NavPolicy.DEFAULT);
            TravelTimes first = cached.estimateBatch(account, from, sites, NavPolicy.DEFAULT);
            TravelTimes again = cached.estimateBatch(
                    account.at(from.x(), from.y()), from, new ArrayList<>(sites), NavPolicy.DEFAULT);
            repeats++;
            if (!expected.equals(first) || !expected.equals(again)) {
                mismatches.add("account " + a + " from " + from + ": uncached " + expected + ", cached " + first
                        + ", again " + again);
            }
            AccountFacts other = PrereqProgramTest.account(env, rnd, gateItems, gateQuests);
            TravelTimes otherExpected = uncached.estimateBatch(other, from, sites, NavPolicy.DEFAULT);
            TravelTimes otherCached = cached.estimateBatch(other, from, sites, NavPolicy.DEFAULT);
            if (!otherExpected.equals(otherCached)) {
                mismatches.add("other account " + a + " from " + from + ": uncached " + otherExpected + ", cached "
                        + otherCached);
            }
        }
        assertEquals(List.of(), mismatches);
        long hits = cached.batchRowHits() - hitsBefore;
        assertEquals(true, hits >= repeats, "every repeat is a hit: " + hits + " of " + repeats);
    }

    @Test
    void levelsAreReadByValueAndContainersByIdentity() {
        ServerData env = RealDefs.serverData();
        Engine cached = (Engine) Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET))
                .nav();
        Engine uncached = (Engine) RealDefs.pathfinder().nav();
        List<Dest> sites = List.of(Point.of(122, 647), Point.of(310, 545), Point.of(220, 640), Point.of(651, 848));
        Point from = Point.of(126, 670);
        MutableInventory pack = new MutableInventory();
        pack.setSlot(0, new InventorySlot(0, ItemId.COINS.id(), 50, false));
        MutableBank banked = new MutableBank();
        banked.setSlot(0, new BankSlot(0, ItemId.COINS, 500));
        Inventory inventory = pack.snapshot();
        Bank bank = banked.snapshot();
        Quests quests = new MutableQuests().snapshot();
        AccountFacts low = new AccountFacts.Snapshot(env, inventory, bank, stats(1, 0), quests, from);
        AccountFacts lowMoreXp = new AccountFacts.Snapshot(env, inventory, bank, stats(1, 90), quests, from);
        AccountFacts high = new AccountFacts.Snapshot(env, inventory, bank, stats(70, 0), quests, from);

        long before = cached.batchRowHits();
        assertEquals(
                uncached.estimateBatch(low, from, sites, NavPolicy.DEFAULT),
                cached.estimateBatch(low, from, sites, NavPolicy.DEFAULT));
        assertEquals(
                uncached.estimateBatch(lowMoreXp, from, sites, NavPolicy.DEFAULT),
                cached.estimateBatch(lowMoreXp, from, sites, NavPolicy.DEFAULT));
        assertEquals(1, cached.batchRowHits() - before, "equal levels in a new Stats are a hit");
        assertEquals(
                uncached.estimateBatch(high, from, sites, NavPolicy.DEFAULT),
                cached.estimateBatch(high, from, sites, NavPolicy.DEFAULT));
        assertEquals(1, cached.batchRowHits() - before, "different levels are a miss");
    }

    @Test
    void aNewPackWithTheSameClassesIsAnsweredByTheirRow() {
        ServerData env = RealDefs.serverData();
        Engine cached = (Engine) Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET))
                .nav();
        Engine uncached = (Engine) RealDefs.pathfinder().nav();
        List<Dest> sites = List.of(Point.of(122, 647), Point.of(310, 545), Point.of(220, 640), Point.of(651, 848));
        Point from = Point.of(126, 670);
        MutableBank banked = new MutableBank();
        banked.setSlot(0, new BankSlot(0, ItemId.COINS, 500));
        Bank bank = banked.snapshot();
        Quests quests = new MutableQuests().snapshot();
        Stats stats = stats(40, 0);
        AccountFacts before = new AccountFacts.Snapshot(env, pack(50, false), bank, stats, quests, from);
        AccountFacts gathered = new AccountFacts.Snapshot(env, pack(50, true), bank, stats, quests, from);
        AccountFacts richer = new AccountFacts.Snapshot(env, pack(5000, false), bank, stats, quests, from);

        long rows = cached.batchRowHits();
        long classes = cached.classRowHits();
        assertEquals(
                uncached.estimateBatch(before, from, sites, NavPolicy.DEFAULT),
                cached.estimateBatch(before, from, sites, NavPolicy.DEFAULT));
        assertEquals(
                uncached.estimateBatch(gathered, from, sites, NavPolicy.DEFAULT),
                cached.estimateBatch(gathered, from, sites, NavPolicy.DEFAULT));
        assertEquals(0, cached.batchRowHits() - rows, "an ore in the pack is a new pack object");
        assertEquals(1, cached.classRowHits() - classes, "and changes no class: the class row answers");
        assertEquals(
                uncached.estimateBatch(richer, from, sites, NavPolicy.DEFAULT),
                cached.estimateBatch(richer, from, sites, NavPolicy.DEFAULT));
        assertEquals(1, cached.classRowHits() - classes, "coins past a fare's threshold: another class");
    }

    private static Inventory pack(int coins, boolean ore) {
        MutableInventory m = new MutableInventory();
        m.setSlot(0, new InventorySlot(0, ItemId.COINS.id(), coins, false));
        if (ore) {
            m.setSlot(1, new InventorySlot(1, ItemId.COPPER_ORE.id(), 1, false));
        }
        return m.snapshot();
    }

    private static Stats stats(int level, int xp) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            stats.setSkill(sk.ordinal(), level, level, xp);
        }
        return stats.snapshot();
    }
}
