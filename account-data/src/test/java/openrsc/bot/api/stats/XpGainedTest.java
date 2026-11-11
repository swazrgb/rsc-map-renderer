package openrsc.bot.api.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class XpGainedTest {

    @Test
    void aGainIsTheDifference() {
        assertEquals(400, Stats.xpGained(1_000, 1_400));
        assertEquals(400, Stats.xpGained(3_000_000_000L, 3_000_000_400L));
    }

    @Test
    void aGainAcrossTheSignBitIsTheDifference() {
        assertEquals(400, Stats.xpGained(0x7FFF_FF00L, 0x8000_0090L));
    }

    @Test
    void aSkillThatPassedTheLimitGainedWhatItTookToGetThere() {
        assertEquals(400, Stats.xpGained(Stats.XP_LIMIT - 99, 300));
    }

    @Test
    void aFallIsNegative() {
        assertEquals(-400, Stats.xpGained(3_000_000_400L, 3_000_000_000L));
    }
}
