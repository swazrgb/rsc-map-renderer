package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StringPullTest {

    private static CollisionMap map;
    private static WindowSearch search;
    private static StepView plain;
    private static Point a;

    private static Point b;

    @BeforeAll
    static void load() {
        ServerData env = RealDefs.serverData();
        map = env.collisionMap();
        search = new WindowSearch(map);
        plain = StepView.ofMap(map, Planner.DangerCharge.NONE);
        outer:
        for (int z = 600; z < 720; z += 3) {
            for (int x = 60; x < 200; x += 3) {
                Point from = Point.of(x, z);
                // Both axes move, unequally, so a stair of cardinals and diagonals is as short as the line.
                Point to = Point.of(x + 17, z + 6);
                if (map.fullyBlocked(from.x(), from.y()) || map.fullyBlocked(to.x(), to.y())) {
                    continue;
                }
                if (WindowSearch.line(from, to, plain, Rect.of(to, to), true) != null) {
                    a = from;
                    b = to;
                    break outer;
                }
            }
        }
        assertNotNull(a, "an open 17x6 pair somewhere between Lumbridge and Al Kharid");
    }

    @Test
    @DisplayName("across open ground the walk is the straight line, one tick a tile")
    void openGroundWalksTheLine() {
        List<Point> walk = search.run(plain, a, Rect.of(b, b), null, null, true, WindowSearch.Momentum.NONE);
        assertNotNull(walk);
        List<Point> line = WindowSearch.line(a, b, plain, Rect.of(b, b), true);
        assertEquals(Point.chebyshev(a.x(), a.y(), b.x(), b.y()) + 1, walk.size(), "as many ticks as the distance");
        assertEquals(line, walk.subList(1, walk.size()), "and every tile on the line");
        adjacent(walk);
    }

    @Test
    @DisplayName("a pull never crosses what the walk detoured round")
    void aPullKeepsTheDetour() {
        int midX = (a.x() + b.x()) / 2;
        Rect strip = new Rect(midX, Math.min(a.y(), b.y()) - 3, midX, Math.max(a.y(), b.y()) + 3);
        Planner.DangerCharge charge = (x, y) -> strip.contains(x, y) ? 500 : 0;
        StepView charged = StepView.ofMap(map, charge);
        List<Point> walk = search.run(charged, a, Rect.of(b, b), null, null, true, WindowSearch.Momentum.NONE);
        assertNotNull(walk);
        adjacent(walk);
        long paid = 0;
        for (Point p : walk) {
            paid += charge.at(p.x(), p.y());
        }
        List<Point> line = WindowSearch.line(a, b, charged, Rect.of(b, b), true);
        long linePays = 0;
        for (Point p : line) {
            linePays += charge.at(p.x(), p.y());
        }
        assertTrue(linePays > 0, "the premise: the straight line crosses the strip");
        assertTrue(paid < linePays, "the walk pays " + paid + " where the line would pay " + linePays);
    }

    @Test
    @DisplayName("a body on the line stays a wall after the pull")
    void aBodyOnTheLineIsNotWalkedThrough() {
        List<Point> line = WindowSearch.line(a, b, plain, Rect.of(b, b), true);
        Point body = line.get(line.size() / 2);
        StepView blocked = new StepView() {
            @Override
            public boolean canStep(int fx, int fy, int tx, int ty) {
                return plain.canStep(fx, fy, tx, ty);
            }

            @Override
            public boolean occupied(int x, int y) {
                return x == body.x() && y == body.y();
            }

            @Override
            public int extra(int x, int y) {
                return 0;
            }
        };
        List<Point> walk = search.run(blocked, a, Rect.of(b, b), null, null, true, WindowSearch.Momentum.NONE);
        assertNotNull(walk);
        adjacent(walk);
        assertTrue(walk.stream().noneMatch(body::equals), "steps round the body: " + walk);
    }

    private static void adjacent(List<Point> walk) {
        for (int i = 1; i < walk.size(); i++) {
            Point p = walk.get(i - 1);
            Point q = walk.get(i);
            assertTrue(Point.chebyshev(p.x(), p.y(), q.x(), q.y()) == 1, "one tile a tick: " + p + " -> " + q);
            assertTrue(plain.canStep(p.x(), p.y(), q.x(), q.y()), "a legal step: " + p + " -> " + q);
        }
    }
}
