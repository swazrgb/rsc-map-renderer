package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;

class MomentumTieTest {

    // From FORK, two equally short ways lead round the trees to WEB.
    private static final Point FORK = Point.of(137, 139);
    private static final Rect WEB = Rect.of(Point.of(176, 106), Point.of(176, 106));

    @Test
    void anEqualTieKeepsTheWayTheWalkWasHeading() {
        CollisionMap map = RealDefs.serverData().collisionMap();
        WindowSearch search = new WindowSearch(map);
        StepView plain = StepView.ofMap(map, Planner.DangerCharge.NONE);

        List<Point> east = search.run(plain, FORK, WEB, null, null, true, new WindowSearch.Momentum(1, 0));
        List<Point> west = search.run(plain, FORK, WEB, null, null, true, new WindowSearch.Momentum(-1, 0));
        assertNotNull(east);
        assertNotNull(west);
        assertEquals(west.size(), east.size(), "the premise: the two ways round the trees are a tie");

        assertTrue(east.get(1).x() > FORK.x(), "heading east, the tie goes east: " + east.subList(0, 4));
        assertTrue(west.get(1).x() < FORK.x(), "heading west, the tie goes west: " + west.subList(0, 4));
    }
}
