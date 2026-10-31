package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.BankArea;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.Test;

class AreaWalkBench {

    private static final Rect DRAYNOR = BankArea.DRAYNOR.boxes().getFirst();
    private static final Point START = Point.of(175, 640);

    @Test
    void leadPerTick() {
        ServerData env = RealDefs.serverData();
        Pathfinder pf = RealDefs.cachedPathfinder();
        List<Point> path = pathInto(env.collisionMap(), START, DRAYNOR);
        AccountFacts facts = NavProbe.onFootFacts(env, START.x(), START.y());
        NavPolicy danger = NavPolicy.DEFAULT;
        for (int round = 0; round < 3; round++) {
            System.out.printf(
                    "round %d, %d tiles: plain %.1f us, danger %.1f us%n",
                    round,
                    path.size(),
                    perTick(path, p -> pf.nav().walk(p, DRAYNOR)),
                    perTick(path, p -> pf.nav().walk(p, DRAYNOR, facts, danger)));
        }
    }

    private static double perTick(List<Point> path, java.util.function.Function<Point, Planner.Leg> walk) {
        long total = 0;
        int n = 0;
        for (int rep = 0; rep < 20; rep++) {
            for (Point p : path) {
                long t0 = System.nanoTime();
                Planner.Leg leg = walk.apply(p);
                total += System.nanoTime() - t0;
                n++;
                if (!DRAYNOR.contains(p.x(), p.y())) {
                    assertNotNull(leg, "walks from " + p);
                }
            }
        }
        return total / 1000.0 / n;
    }

    private static List<Point> pathInto(CollisionMap map, Point from, Rect area) {
        java.util.Map<Point, Point> prev = new java.util.HashMap<>();
        ArrayDeque<Point> queue = new ArrayDeque<>(List.of(from));
        prev.put(from, from);
        while (!queue.isEmpty()) {
            Point p = queue.poll();
            if (area.contains(p.x(), p.y())) {
                List<Point> out = new ArrayList<>();
                for (Point q = p; !q.equals(from); q = prev.get(q)) {
                    out.add(q);
                }
                out.add(from);
                Collections.reverse(out);
                return out;
            }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    Point n = Point.of(p.x() + dx, p.y() + dy);
                    if ((dx != 0 || dy != 0) && !prev.containsKey(n) && map.canStep(p.x(), p.y(), n.x(), n.y())) {
                        prev.put(n, p);
                        queue.add(n);
                    }
                }
            }
        }
        throw new AssertionError("no walk from " + from + " into " + area);
    }
}
