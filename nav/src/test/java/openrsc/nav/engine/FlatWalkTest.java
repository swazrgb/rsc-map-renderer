package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.SplittableRandom;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FlatWalkTest {

    @Test
    @DisplayName("agrees with an uncharged field on every goal of a window")
    void agreesWithTheField() {
        CollisionMap map = RealDefs.serverData().collisionMap();
        StepMasks steps = new StepMasks(map);
        FlatWalk walk = new FlatWalk();
        SplittableRandom rnd = new SplittableRandom(20260916L);
        int compared = 0;
        int reached = 0;
        while (compared < 4000) {
            int fx = rnd.nextInt(CollisionMap.WIDTH);
            int fy = rnd.nextInt(CollisionMap.FLOOR_HEIGHT);
            if (!map.inBounds(fx, fy) || map.fullyBlocked(fx, fy)) {
                continue;
            }
            Point from = Point.of(fx, fy);
            int r = LocalField.MAX_RADIUS;
            LocalField field = LocalField.build(steps, map, null, from, r);
            int x0 = Math.max(0, fx - r);
            int y0 = Math.max(0, fy - r);
            int x1 = Math.min(map.width() - 1, fx + r);
            int y1 = Math.min(map.height() - 1, fy + r);
            for (int k = 0; k < 40; k++) {
                int gx = x0 + rnd.nextInt(x1 - x0 + 1);
                int gy = y0 + rnd.nextInt(y1 - y0 + 1);
                int w = field.weight(gx, gy);
                int s = walk.steps(steps, x0, y0, x1, y1, fx, fy, gx, gy);
                assertEquals(
                        w == Integer.MAX_VALUE ? -1 : w / LocalField.STEP_COST,
                        s,
                        "from " + from + " to (" + gx + "," + gy + ")");
                compared++;
                if (s >= 0) {
                    reached++;
                }
            }
        }
        assertTrue(reached > 1000, "most sampled goals are walkable: " + reached);
    }
}
