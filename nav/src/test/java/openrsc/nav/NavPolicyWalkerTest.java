package openrsc.nav;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import openrsc.bot.api.stats.MutableStats;
import openrsc.bot.api.stats.Skill;
import openrsc.bot.api.stats.Stats;
import org.junit.jupiter.api.Test;

class NavPolicyWalkerTest {

    private static Stats defense(int level) {
        MutableStats stats = new MutableStats();
        for (Skill sk : Skill.values()) {
            int l = sk == Skill.DEFENSE ? level : 99;
            stats.setSkill(sk.ordinal(), l, l, 0);
        }
        return stats.snapshot();
    }

    @Test
    void aWeakAccountBerthsWhatAggroesIt() {
        NavPolicy pol = NavPolicy.DEFAULT;
        assertSame(pol, pol.forWalker(defense(NavPolicy.DANGER_MAX_DEFENSE)));
    }

    @Test
    void aSturdyAccountWalksThroughWhatAggroesIt() {
        NavPolicy walked = NavPolicy.DEFAULT.forWalker(defense(NavPolicy.DANGER_MAX_DEFENSE + 1));
        assertFalse(walked.berth().berthsAnything(), walked.toString());
        assertTrue(NavPolicy.DEFAULT.berth().berthsAnything(), "the policy itself is left as it was");
    }
}
