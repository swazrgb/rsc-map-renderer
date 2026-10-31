package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import openrsc.gamedata.geometry.Point;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import openrsc.nav.cache.CaffeineNavCaches;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class GnomeFenceWalkTest {
    @ParameterizedTest
    @ValueSource(strings = {"uranium", "rsccoleslaw"})
    void walksIntoVillageFromOutsideTheFence(String world) {
        var env = world.equals("uranium") ? RealDefs.serverData() : RealDefs.serverData(world);
        assertTrue(env.collisionMap().fullyBlocked(634, 705), "second fence flank is blocked scenery");
        var pf = Pathfinder.load(env, new CaffeineNavCaches(RealDefs.CACHE_BUDGET));
        var walk = NavProbe.planBetween(
                pf,
                639,
                708,
                656,
                695,
                NavProbe.onFootFacts(env, 639, 708),
                NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF));
        assertNotNull(walk);
        var last = walk.getLast();
        assertEquals(Point.of(656, 695), Point.of(last.x(), last.y()), "the planned fence approach must be walkable");
    }
}
