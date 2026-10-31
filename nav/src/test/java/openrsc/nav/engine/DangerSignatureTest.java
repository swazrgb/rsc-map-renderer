package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import openrsc.bot.api.container.Bank;
import openrsc.bot.api.container.Inventory;
import openrsc.bot.api.container.MutableBank;
import openrsc.bot.api.container.MutableInventory;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.quest.MutableQuests;
import openrsc.bot.api.quest.Quests;
import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;

class DangerSignatureTest {

    // One pack, bank and quest log for every account, so the engine's memo may share entries between them.
    private static final Inventory PACK = new MutableInventory().snapshot();
    private static final Bank BANK = new MutableBank().snapshot();
    private static final Quests QUESTS = new MutableQuests().snapshot();

    @Test
    void dangerOffIsOneFieldAtEveryCombatLevel() {
        ServerData env = RealDefs.serverData();
        Engine engine = (Engine) RealDefs.pathfinder().nav();
        AccountFacts low = account(env, 3, Map.of());
        AccountFacts high = account(env, 90, Map.of());
        assertNotEquals(
                engine.dangerMap().rungOf(low.stats().combatLevel()),
                engine.dangerMap().rungOf(high.stats().combatLevel()),
                "the premise: two rungs");

        NavPolicy off = NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF);
        Engine.Resolved lowOff = engine.resolve(low, off);
        Engine.Resolved highOff = engine.resolve(high, off);
        assertSame(lowOff.danger, highOff.danger, "danger off: one field at every level");
        assertEquals(lowOff.danger.signature(), highOff.danger.signature());
        assertFalse(lowOff.danger.anyDanger(), "and it charges nothing");

        NavPolicy on = NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.ON);
        Engine.Resolved lowOn = engine.resolve(low, on);
        Engine.Resolved highOn = engine.resolve(high, on);
        assertNotEquals(lowOn.danger.signature(), highOn.danger.signature(), "danger on: a field per rung");
    }

    @Test
    void theDefaultSparesOnlyASturdyAccount() {
        ServerData env = RealDefs.serverData();
        // Cached, so the engine's memo shares an entry between accounts whose rungs agree.
        Engine engine = (Engine) RealDefs.cachedPathfinder().nav();
        // One combat level on the lowest danger rung, where a charged account must still key apart.
        AccountFacts weak = account(env, 1, Map.of(Skill.DEFENSE, NavPolicy.DANGER_MAX_DEFENSE, Skill.ATTACK, 3));
        AccountFacts sturdy = account(env, 1, Map.of(Skill.DEFENSE, NavPolicy.DANGER_MAX_DEFENSE + 1));
        assertEquals(weak.stats().combatLevel(), sturdy.stats().combatLevel(), "the premise: one combat level");
        assertEquals(0, engine.dangerMap().rungOf(sturdy.stats().combatLevel()), "the premise: the lowest rung");

        assertTrue(engine.resolve(weak, NavPolicy.DEFAULT).danger.anyDanger(), "Defense at the cap: charged");
        NavPolicy off = NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF);
        assertSame(
                engine.resolve(sturdy, off).danger,
                engine.resolve(sturdy, NavPolicy.DEFAULT).danger,
                "above it, the default is danger off");
        assertTrue(
                engine.resolve(sturdy, NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.ON))
                        .danger
                        .anyDanger(),
                "opting in charges it all the same");
    }

    private static AccountFacts account(ServerData env, int level, Map<Skill, Integer> levels) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            int l = levels.getOrDefault(sk, level);
            stats.setSkill(sk.ordinal(), l, l, 0);
        }
        return new AccountFacts.Snapshot(env, PACK, BANK, stats.snapshot(), QUESTS, Point.of(122, 647));
    }
}
