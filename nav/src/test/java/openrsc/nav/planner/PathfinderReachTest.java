package openrsc.nav.planner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PathfinderReachTest {

    private static ServerData env;
    private static Pathfinder pf;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
    }

    @Test
    @DisplayName("from Lumbridge a fresh account reaches its own component, and not every one")
    void freshAccountFromLumbridge() {
        PathfinderPlans.Request req = new PathfinderPlans.Request(
                new PathfinderPlans.Tile(122, 647),
                null,
                null,
                new PathfinderPlans.AccountInput(Map.of(), Map.of(), Map.of(), Map.of(), null),
                null,
                NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF));
        long t0 = System.nanoTime();
        PathfinderReach.Response r = PathfinderReach.of(pf, null, req);
        System.out.printf(
                "reachable %d of %d components in %.1f ms (%d ms with representatives)%n",
                r.reachable().length, r.components(), r.ms(), (System.nanoTime() - t0) / 1_000_000);
        CollisionMap map = env.collisionMap();
        int own = map.componentId(122, 647);
        assertTrue(own > 0, "the premise: Lumbridge is walkable");
        assertTrue(Arrays.binarySearch(r.reachable(), own) >= 0, "its own component");
        int varrock = map.componentId(122, 504);
        assertTrue(Arrays.binarySearch(r.reachable(), varrock) >= 0, "Varrock, on foot");
        assertTrue(r.reachable().length > 1, "more than where it stands");
        assertFalse(r.reachable().length == r.components(), "and not everything");

        PathfinderReach.Response again = PathfinderReach.of(pf, null, req);
        System.out.printf("again: %.1f ms%n", again.ms());
        assertTrue(Arrays.equals(r.reachable(), again.reachable()), "the same answer");
    }
}
