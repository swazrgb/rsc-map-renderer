package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.SplittableRandom;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.SkillBreakpoints;
import org.junit.jupiter.api.Test;

class SkillBreakpointsTest {

    @Test
    void rungsAreTheDeclaredThresholdsAndLevelsOnOneRungDecideTheSameClass() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET));
        Engine cached = (Engine) pf.nav();
        Engine uncached = (Engine) RealDefs.pathfinder().nav();
        LinkedHashSet<Fact> facts = new LinkedHashSet<>();
        for (Transport t : cached.graph().transports()) {
            for (Fact f : t.prereq().inputs().facts()) {
                facts.add(f);
            }
        }
        SkillBreakpoints breakpoints = SkillBreakpoints.of(facts);
        for (Fact f : facts) {
            if (f.kind() == Fact.Kind.SKILL_AT_LEAST) {
                int[] rungs = breakpoints.current(f.skill());
                assertTrue(Arrays.binarySearch(rungs, f.threshold()) >= 0, "declared: " + f);
                assertTrue(
                        breakpoints.currentRung(f.skill(), f.threshold())
                                > breakpoints.currentRung(f.skill(), f.threshold() - 1),
                        "a rung starts at " + f);
            }
        }
        assertTrue(breakpoints.current(Skill.AGILITY).length > 3, "agility gates exist");

        int[] gateItems = pf.eligibilityItemIds();
        int[] gateQuests = pf.eligibilityQuestIds();
        SplittableRandom rnd = new SplittableRandom(15);
        for (int a = 0; a < 40; a++) {
            AccountFacts account = PrereqProgramTest.account(env, rnd, gateItems, gateQuests);
            AccountFacts sameRungs = withStats(env, account, withinRungs(account.stats(), breakpoints, rnd));
            Engine.Resolved first = cached.resolve(account, NavPolicy.DEFAULT);
            Engine.Resolved again = cached.resolve(sameRungs, NavPolicy.DEFAULT);
            assertSame(first.factBits, again.factBits, "account " + a + ": same rungs share the entry");
            assertArrayEquals(
                    uncached.resolve(account, NavPolicy.DEFAULT).factBits,
                    uncached.resolve(sameRungs, NavPolicy.DEFAULT).factBits,
                    "account " + a + ": same rungs decide the same facts");

            AccountFacts crossed = withStats(env, account, acrossAgility(account.stats(), breakpoints));
            assertNotSame(
                    first.factBits,
                    cached.resolve(crossed, NavPolicy.DEFAULT).factBits,
                    "account " + a + ": an agility threshold crossed is resolved afresh");
        }
    }

    @Test
    void theTotalLevelHasARungWhenAGateReadsIt() {
        SkillBreakpoints breakpoints = SkillBreakpoints.of(java.util.List.of(Fact.totalLevelAtLeast(100)));
        // Twenty skills: totals of 80, 100 and 180 against the gate's 100.
        Stats below = everySkillAt(4);
        Stats at = everySkillAt(5);
        Stats above = everySkillAt(9);
        assertTrue(
                !Arrays.equals(breakpoints.rungs(below, 0), breakpoints.rungs(at, 0)),
                "crossing the gate's total is another account");
        assertArrayEquals(breakpoints.rungs(at, 0), breakpoints.rungs(above, 0), "levels past it share the rung");
        assertArrayEquals(new int[0], SkillBreakpoints.of(new Fact[0]).rungs(above, 0), "no total gate, no total rung");
    }

    private static Stats everySkillAt(int level) {
        MutableStats out = new MutableStats();
        for (Skill sk : Skill.values()) {
            out.setSkill(sk.ordinal(), level, level, 0);
        }
        return out.snapshot();
    }

    private static Stats withinRungs(Stats stats, SkillBreakpoints breakpoints, SplittableRandom rnd) {
        MutableStats out = new MutableStats();
        for (Skill sk : Skill.values()) {
            int level = stats.level(sk.ordinal());
            int[] t = breakpoints.current(sk);
            int lo = 1;
            int hi = 99;
            for (int threshold : t) {
                if (threshold <= level) {
                    lo = Math.max(lo, threshold);
                } else {
                    hi = Math.min(hi, threshold - 1);
                }
            }
            int moved = t.length == 0 ? 1 + rnd.nextInt(99) : lo + rnd.nextInt(Math.max(1, hi - lo + 1));
            out.setSkill(sk.ordinal(), moved, stats.baseLevel(sk.ordinal()), 12345);
        }
        return out.snapshot();
    }

    private static Stats acrossAgility(Stats stats, SkillBreakpoints breakpoints) {
        int lowest = breakpoints.current(Skill.AGILITY)[0];
        MutableStats out = new MutableStats();
        for (Skill sk : Skill.values()) {
            int level = stats.level(sk.ordinal());
            if (sk == Skill.AGILITY) {
                level = level >= lowest ? lowest - 1 : lowest;
            }
            out.setSkill(sk.ordinal(), level, stats.baseLevel(sk.ordinal()), 0);
        }
        return out.snapshot();
    }

    private static AccountFacts withStats(ServerData env, AccountFacts account, Stats stats) {
        return new AccountFacts.Snapshot(
                env, account.inventory(), account.bank(), stats, account.quests(), account.pos());
    }
}
