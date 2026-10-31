package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SplittableRandom;
import openrsc.bot.api.container.BankSlot;
import openrsc.bot.api.container.InventorySlot;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.eligibility.RouteContext;
import org.junit.jupiter.api.Test;

class PrereqProgramTest {

    @Test
    void compiledGatesAgreeWithTheirTreesCarriedAndBanked() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        Engine engine = (Engine) pf.nav();
        LinkedHashSet<Prereq> unique = new LinkedHashSet<>();
        for (Transport t : engine.graph().transports()) {
            unique.add(t.prereq());
        }
        List<Prereq> trees = new ArrayList<>(unique);
        LinkedHashMap<Fact, Integer> numbers = new LinkedHashMap<>();
        List<int[]> programs = new ArrayList<>();
        int longest = 1;
        for (Prereq p : trees) {
            int[] program = p.compile(f -> numbers.computeIfAbsent(f, k -> numbers.size()));
            programs.add(program);
            longest = Math.max(longest, program.length);
        }
        Fact[] facts = numbers.keySet().toArray(new Fact[0]);
        int[] gateItems = pf.eligibilityItemIds();
        int[] gateQuests = pf.eligibilityQuestIds();

        SplittableRandom rnd = new SplittableRandom(20260914L);
        boolean[] stack = new boolean[longest];
        List<String> disagreements = new ArrayList<>();
        int passedCarried = 0;
        int passedBanked = 0;
        for (int a = 0; a < 400; a++) {
            RouteContext ctx = new RouteContext(account(env, rnd, gateItems, gateQuests), NavPolicy.DEFAULT, env);
            long[] carried = new long[(facts.length + 63) >>> 6];
            for (int i = 0; i < facts.length; i++) {
                if (facts[i].test(ctx)) {
                    carried[i >>> 6] |= 1L << i;
                }
            }
            long[] banked = carried.clone();
            for (int i = 0; i < facts.length; i++) {
                boolean withBank = facts[i].testBanked(ctx);
                if (facts[i].bankRelaxed()) {
                    if (withBank) {
                        banked[i >>> 6] |= 1L << i;
                    } else {
                        banked[i >>> 6] &= ~(1L << i);
                    }
                } else if (withBank != facts[i].test(ctx)) {
                    disagreements.add(
                            "account " + a + ": " + facts[i] + " is not bank-relaxed but its bank" + " answer differs");
                }
            }
            for (int p = 0; p < trees.size(); p++) {
                boolean tree = trees.get(p).test(ctx);
                boolean treeBanked = trees.get(p).testBanked(ctx);
                if (Prereq.run(programs.get(p), carried, stack) != tree) {
                    disagreements.add("account " + a + " carried: " + trees.get(p));
                }
                if (Prereq.run(programs.get(p), banked, stack) != treeBanked) {
                    disagreements.add("account " + a + " banked: " + trees.get(p));
                }
                passedCarried += tree ? 1 : 0;
                passedBanked += treeBanked ? 1 : 0;
            }
        }
        assertEquals(
                List.of(),
                disagreements.subList(0, Math.min(10, disagreements.size())),
                disagreements.size() + " disagreements");
        assertTrue(
                passedBanked > passedCarried,
                "the accounts never let a bank open a gate (" + passedCarried + " carried, " + passedBanked
                        + " banked passes) — the banked half was not exercised");
    }

    @Test
    void onlyPackFactsAnswerDifferentlyForAnotherPack() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.pathfinder();
        Engine engine = (Engine) pf.nav();
        LinkedHashSet<Fact> facts = new LinkedHashSet<>();
        for (Transport t : engine.graph().transports()) {
            for (Fact f : t.prereq().inputs().facts()) {
                facts.add(f);
            }
        }
        int[] gateItems = pf.eligibilityItemIds();
        int[] gateQuests = pf.eligibilityQuestIds();
        SplittableRandom rnd = new SplittableRandom(20260915L);
        List<String> disagreements = new ArrayList<>();
        int packAnswers = 0;
        for (int a = 0; a < 400; a++) {
            AccountFacts account = account(env, rnd, gateItems, gateQuests);
            AccountFacts other = account(env, rnd, gateItems, gateQuests);
            RouteContext ctx = new RouteContext(account, NavPolicy.DEFAULT, env);
            RouteContext repacked = ctx.withAccount(account.withInventory(other.inventory()));
            for (Fact f : facts) {
                if (f.test(ctx) != f.test(repacked)) {
                    packAnswers++;
                    if (!f.readsPack()) {
                        disagreements.add("account " + a + ": " + f);
                    }
                }
            }
        }
        assertEquals(
                List.of(),
                disagreements.subList(0, Math.min(10, disagreements.size())),
                disagreements.size() + " disagreements");
        assertTrue(packAnswers > 0, "no pack ever changed an answer — the check was not exercised");
    }

    static AccountFacts account(ServerData env, SplittableRandom rnd, int[] gateItems, int[] gateQuests) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            int level = 1 + rnd.nextInt(99);
            stats.setSkill(sk.ordinal(), level, level, 0);
        }
        MutableQuests quests = new MutableQuests();
        for (int q : gateQuests) {
            switch (rnd.nextInt(4)) {
                case 1 -> quests.setStage(q, 1 + rnd.nextInt(9));
                case 2 -> quests.setStage(q, -1);
                default -> {}
            }
        }
        MutableInventory inv = new MutableInventory();
        int slot = 0;
        inv.setSlot(slot, new InventorySlot(slot, ItemId.COINS.id(), rnd.nextInt(200), false));
        slot++;
        for (int id : gateItems) {
            if (slot < 30 && rnd.nextInt(8) == 0) {
                int amount = env.isStackable(id) ? 1 + rnd.nextInt(60) : 1;
                inv.setSlot(slot, new InventorySlot(slot, id, amount, rnd.nextInt(3) == 0));
                slot++;
            }
        }
        MutableBank bank = new MutableBank();
        int b = 0;
        bank.setSlot(b, new BankSlot(b, ItemId.COINS, rnd.nextInt(3000)));
        b++;
        for (int id : gateItems) {
            ItemId known = ItemId.getById(id);
            if (known != null && rnd.nextInt(3) == 0) {
                bank.setSlot(b, new BankSlot(b, known, env.isStackable(id) ? 1 + rnd.nextInt(300) : 1));
                b++;
            }
        }
        Point at = Point.of(50 + rnd.nextInt(850), 50 + rnd.nextInt(850));
        return new AccountFacts.Snapshot(env, inv.snapshot(), bank.snapshot(), stats.snapshot(), quests.snapshot(), at);
    }
}
