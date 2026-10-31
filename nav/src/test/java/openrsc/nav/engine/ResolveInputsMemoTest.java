package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.SplittableRandom;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.cache.CaffeineNavCaches;
import org.junit.jupiter.api.Test;

class ResolveInputsMemoTest {

    @Test
    void anotherObjectWithAnEntrysInputsSharesItsClassesAndNothingElseDoes() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET));
        Engine engine = (Engine) pf.nav();
        int[] gateItems = pf.eligibilityItemIds();
        int[] gateQuests = pf.eligibilityQuestIds();
        Point deep = null;
        for (int z = 0; deep == null && z < 1000; z++) {
            if (AccountFacts.wildernessLevel(200, z) >= 30) {
                deep = Point.of(200, z);
            }
        }
        assertNotNull(deep, "no tile 30 deep in the wilderness");
        SplittableRandom rnd = new SplittableRandom(29);
        for (int a = 0; a < 40; a++) {
            AccountFacts base = PrereqProgramTest.account(env, rnd, gateItems, gateQuests);
            Point here = Point.of(120 + a, 650);
            Point step = Point.of(121 + a, 650);
            assertEquals(
                    AccountFacts.wildernessLevel(here.x(), here.y()),
                    AccountFacts.wildernessLevel(step.x(), step.y()),
                    "one step along a row");

            Engine.Resolved first = engine.resolve(base.at(here.x(), here.y()), NavPolicy.DEFAULT);
            AccountFacts stepped = base.at(step.x(), step.y());
            Engine.Resolved again = engine.resolve(stepped, NavPolicy.DEFAULT);
            assertNotSame(first, again);
            assertSame(stepped, again.account);
            assertSame(stepped, again.facts.account());
            assertSame(first.factBits, again.factBits, "account " + a + ": the same inputs share");
            assertSame(first.canUse, again.canUse);

            for (int k = 0; k < 8; k++) {
                engine.resolve(PrereqProgramTest.account(env, rnd, gateItems, gateQuests), NavPolicy.DEFAULT);
            }
            Engine.Resolved scratch = engine.resolve(base.at(here.x(), here.y()), NavPolicy.DEFAULT);
            assertNotSame(first.factBits, scratch.factBits, "account " + a + ": evicted");
            assertArrayEquals(first.factBits, scratch.factBits);
            assertArrayEquals(first.canUse, scratch.canUse);
            assertArrayEquals(first.tCosts, scratch.tCosts);
            assertSame(first.danger, scratch.danger);

            // Position is no input: where a teleport may be cast is its region, built into the graph.
            Engine.Resolved deeper = engine.resolve(base.at(deep.x(), deep.y()), NavPolicy.DEFAULT);
            assertSame(scratch.factBits, deeper.factBits, "account " + a + ": depth is no input");

            Stats stats = base.stats();
            MutableStats moreXp = new MutableStats();
            MutableStats moved = new MutableStats();
            for (Skill sk : Skill.values()) {
                int i = sk.ordinal();
                int level = stats.level(i);
                int attack = sk != Skill.ATTACK ? level : level > 1 ? level - 1 : level + 1;
                moreXp.setSkill(i, attack, stats.baseLevel(i), 777);
                int agility = sk != Skill.AGILITY ? level : level >= 5 ? 1 : 99;
                moved.setSkill(i, agility, stats.baseLevel(i), 0);
            }
            Engine.Resolved xp = engine.resolve(
                    new AccountFacts.Snapshot(
                            env, base.inventory(), base.bank(), moreXp.snapshot(), base.quests(), here),
                    NavPolicy.DEFAULT);
            assertSame(scratch.factBits, xp.factBits, "account " + a + ": XP and an ungated level moved");
            Engine.Resolved levelled = engine.resolve(
                    new AccountFacts.Snapshot(
                            env, base.inventory(), base.bank(), moved.snapshot(), base.quests(), here),
                    NavPolicy.DEFAULT);
            assertNotSame(scratch.factBits, levelled.factBits, "account " + a + ": agility moved across its gates");
        }
    }
}
