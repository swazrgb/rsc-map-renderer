package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.cache.CacheValues;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.RealDefs;
import openrsc.nav.planner.InputsProbeBot;
import org.junit.jupiter.api.Test;

class CanKillAtTest {

    // Either side of the Legends' ruined wall, whose failed jump deals 6:
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestWallObjects.java#L121
    private static final Point WEST = Point.of(455, 3729);

    private static final Point EAST = Point.of(457, 3727);

    @Test
    void aWallThatCanKillIsNotJumpedOnTheHitsItWouldKill() {
        assertFalse(plans(6), "6 damage on a failed jump kills on 6 hits");
        assertTrue(plans(7), "and cannot on 7");
    }

    private static boolean plans(int hits) {
        AccountFacts bot = maxedOn(hits);
        return RealDefs.pathfinder().nav().plan(new PlanRequest(bot, WEST, EAST, LoadoutSpec.any(), NavPolicy.DEFAULT))
                instanceof PlanResult.Planned;
    }

    private static AccountFacts maxedOn(int hits) {
        Map<Skill, Integer> skills = new LinkedHashMap<>();
        for (Skill s : Skill.values()) {
            skills.put(s, s == Skill.HITS ? hits : 99);
        }
        Map<Integer, Integer> quests = new LinkedHashMap<>();
        for (int q = 0; q < 60; q++) {
            quests.put(q, -1);
        }
        return new InputsProbeBot(
                RealDefs.serverData(), skills, Map.of(), Set.of(), Map.of(), quests, CacheValues.EMPTY);
    }
}
