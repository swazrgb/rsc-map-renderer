package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.TravelTime;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DangerTimeFloorTest {

    // Lumbridge, Varrock, Falador, Catherby and Ardougne, and one leg across the map, walked by an
    // account fresh enough that most of the map charges it.
    private static final int[][] LEGS = {
        {120, 650, 135, 505},
        {120, 650, 300, 550},
        {135, 505, 300, 550},
        {300, 550, 440, 500},
        {440, 500, 550, 580},
        {120, 650, 550, 580},
        {97, 100, 597, 859},
    };

    private static ServerData env;
    private static Pathfinder pf;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.cachedPathfinder();
    }

    @Test
    @DisplayName("under danger a route never reports less time than the shortest walk")
    void dangerNeverReportsLessTimeThanTheShortestWalk() {
        for (int[] leg : LEGS) {
            AccountFacts facts = NavProbe.onFootFacts(env, leg[0], leg[1]);
            TravelTime off = estimate(facts, leg, NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF));
            TravelTime on = estimate(facts, leg, NavPolicy.DEFAULT);
            assertTrue(
                    off.isReachable() && on.isReachable() && off.deci() > 0 && on.deci() > 0,
                    "both reachable: " + leg(leg) + " off=" + off + " on=" + on);
            assertTrue(on.deci() >= off.deci(), leg(leg) + ": danger ON reports " + on + ", OFF " + off);
        }
    }

    @Test
    @DisplayName("a walk danger does not touch reports the same time either way")
    void anUntouchedWalkReportsTheSameTimeEitherWay() {
        int[] leg = LEGS[0];
        AccountFacts facts = NavProbe.onFootFacts(env, leg[0], leg[1]);
        assertEquals(
                estimate(facts, leg, NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF)),
                estimate(facts, leg, NavPolicy.DEFAULT),
                leg(leg));
    }

    private static TravelTime estimate(AccountFacts facts, int[] leg, NavPolicy policy) {
        return pf.nav()
                .estimate(facts, Point.of(leg[0], leg[1]), Point.of(leg[2], leg[3]), NavProbe.policyFor(facts, policy));
    }

    private static String leg(int[] leg) {
        return leg[0] + "," + leg[1] + " -> " + leg[2] + "," + leg[3];
    }
}
