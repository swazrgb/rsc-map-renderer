package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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

class FieldWalkTest {

    private static final int RANGE = 15;
    private static final int SPAN = 40;

    private static CollisionMap map;
    private static WindowSearch search;
    private static Point a;
    private static Point b;

    @BeforeAll
    static void load() {
        ServerData env = RealDefs.serverData();
        map = env.collisionMap();
        search = new WindowSearch(map);
        StepView plain = StepView.ofMap(map, Planner.DangerCharge.NONE);
        assertEquals(RANGE, env.world().mobRange(), "the circle this test is written for");
        outer:
        for (int z = 40; z < 760; z += 4) {
            for (int x = 40; x < 330; x += 4) {
                Point from = Point.of(x, z);
                Point to = Point.of(x + SPAN, z);
                if (map.fullyBlocked(from.x(), from.y())
                        || map.fullyBlocked(to.x(), to.y())
                        || WindowSearch.line(from, to, plain, Rect.of(to, to), true) == null) {
                    continue;
                }
                Rect walled = deepField(from);
                StepView round = new StepView() {
                    @Override
                    public boolean canStep(int fx, int fy, int tx, int ty) {
                        return plain.canStep(fx, fy, tx, ty);
                    }

                    @Override
                    public boolean occupied(int x, int y) {
                        return walled.contains(x, y);
                    }

                    @Override
                    public int extra(int x, int y) {
                        return 0;
                    }
                };
                if (search.run(round, from, Rect.of(to, to), null, null, true, WindowSearch.Momentum.NONE) != null) {
                    a = from;
                    b = to;
                    break outer;
                }
            }
        }
        assertNotNull(
                a,
                "an open march of " + SPAN + " tiles somewhere on the surface, with a way "
                        + "round a field across it");
    }

    // 24 deep along the march, so from its near edge its far 9 columns are still beyond the circle.
    private static Rect deepField(Point from) {
        return new Rect(from.x() + 8, from.y() - 6, from.x() + 31, from.y() + 6);
    }

    @Test
    @DisplayName("a field deeper than the circle is walked round")
    void aDeepFieldIsWalkedRound() {
        Rect field = deepField(a);
        List<Point> walk = march(field);
        assertEquals(b, walk.get(walk.size() - 1), "arrives");
        Rect deep = new Rect(field.minX() + RANGE, field.minY(), field.maxX(), field.maxY());
        assertEquals(0, inside(walk, deep), "never deeper than the circle reaches: " + walk);
    }

    @Test
    @DisplayName("a field the circle swallows is cut through")
    void aShallowFieldIsCutThrough() {
        Rect field = new Rect(a.x() + 4, a.y() - 14, a.x() + 7, a.y() + 14);
        List<Point> walk = march(field);
        assertEquals(b, walk.get(walk.size() - 1), "arrives");
        assertTrue(inside(walk, field) > 0, "cuts through it: " + walk);
        assertEquals(SPAN + 1, walk.size(), "and pays no ticks for it");
    }

    private static List<Point> march(Rect field) {
        Planner.DangerCharge charge = (x, y) -> field.contains(x, y) ? 400 : 0;
        List<Point> walk = new ArrayList<>();
        Point pos = a;
        walk.add(pos);
        WindowSearch.Momentum momentum = WindowSearch.Momentum.NONE;
        List<Point> path = null;
        for (int tick = 0; tick < 300 && !pos.equals(b); tick++) {
            int at = path == null ? -1 : path.indexOf(pos);
            if (at < 0 || at == path.size() - 1) {
                StepView view = StepView.ofMap(map, StepView.beyond(charge, pos, RANGE));
                path = search.run(view, pos, Rect.of(b, b), null, null, true, momentum);
                assertNotNull(path, "a way on from " + pos);
                at = 0;
            }
            assertTrue(path.size() > at + 1, "a step on from " + pos);
            Point next = path.get(at + 1);
            momentum = WindowSearch.Momentum.of(pos, next);
            pos = next;
            walk.add(pos);
        }
        return walk;
    }

    private static int inside(List<Point> walk, Rect field) {
        int n = 0;
        for (Point p : walk) {
            if (field.contains(p.x(), p.y())) {
                n++;
            }
        }
        return n;
    }
}
