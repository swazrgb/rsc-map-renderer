package openrsc.nav.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LocalFieldUnchargedWindowTest {

    private static CollisionMap map;
    private static StepMasks steps;
    private static DangerField[] views;

    @BeforeAll
    static void load() {
        ServerData env = RealDefs.serverData();
        map = env.collisionMap();
        steps = new StepMasks(map);
        DangerMap dangerMap = DangerMap.build(env.npcSpawns(), env::npc, map);
        // Rung 0 charges every aggressive spawn's tiles; rung 1 leaves the lowest ceiling's out.
        views = new DangerField[] {new DangerField(dangerMap, 0, 100), new DangerField(dangerMap, 1, 100)};
    }

    @Test
    @DisplayName("a window read as uncharged has no charged tile, and both kinds of window occur")
    void unchargedMeansNoChargedTile() {
        int charged = 0;
        int uncharged = 0;
        for (DangerField danger : views) {
            assertTrue(danger.anyDanger(), "no aggressive spawns loaded — the fixture would prove nothing");
            for (int r : new int[] {LocalField.MAX_RADIUS, 7}) {
                for (int y = r; y + r < map.height(); y += 23) {
                    for (int x = r; x + r < map.width(); x += 29) {
                        if (danger.chargesAnyIn(x - r, y - r, x + r, y + r)) {
                            charged++;
                            continue;
                        }
                        uncharged++;
                        for (int ty = y - r; ty <= y + r; ty++) {
                            for (int tx = x - r; tx <= x + r; tx++) {
                                assertEquals(
                                        0,
                                        danger.penaltyAt(tx, ty),
                                        "window at (" + x + "," + y + ") r=" + r + " read uncharged, but (" + tx + ","
                                                + ty + ") is charged");
                            }
                        }
                    }
                }
            }
        }
        assertTrue(charged > 0, "no window was charged");
        assertTrue(uncharged > charged, "uncharged windows " + uncharged + ", charged " + charged);
    }

    @Test
    @DisplayName("an uncharged window prices every tile as the heap walk does")
    void unchargedWindowMatchesTheHeap() {
        int compared = 0;
        DangerField danger = views[0];
        int r = LocalField.MAX_RADIUS;
        for (int y = r; y + r < map.height() && compared < 40; y += 97) {
            for (int x = r; x + r < map.width() && compared < 40; x += 89) {
                if (steps.at(x, y) == 0 || danger.chargesAnyIn(x - r, y - r, x + r, y + r)) {
                    continue;
                }
                LocalField field = LocalField.build(steps, map, danger, Point.of(x, y), r);
                int[][] heap = heapWalk(danger, x, y, r);
                for (int ty = y - r; ty <= y + r; ty++) {
                    for (int tx = x - r; tx <= x + r; tx++) {
                        int i = (ty - (y - r)) * (2 * r + 1) + (tx - (x - r));
                        assertEquals(heap[0][i], field.weight(tx, ty), "weight at " + tx + "," + ty);
                        if (heap[0][i] != Integer.MAX_VALUE) {
                            assertEquals(heap[1][i], field.time(tx, ty), "time at " + tx + "," + ty);
                        }
                    }
                }
                compared++;
            }
        }
        assertTrue(compared >= 20, "windows compared: " + compared);
    }

    @Test
    @DisplayName("an uncharged box's field, under a danger view, is the goal-directed plain walk")
    void unchargedBoxIsTheFlatWalk() {
        DangerField danger = views[0];
        FlatWalk walk = new FlatWalk();
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(20261010L);
        int compared = 0;
        int reached = 0;
        while (compared < 3000) {
            int fx = rnd.nextInt(CollisionMap.WIDTH);
            int fy = rnd.nextInt(CollisionMap.FLOOR_HEIGHT);
            if (!map.inBounds(fx, fy) || map.fullyBlocked(fx, fy)) {
                continue;
            }
            int gx = Math.max(0, Math.min(map.width() - 1, fx + rnd.nextInt(-40, 41)));
            int gy = Math.max(0, Math.min(CollisionMap.FLOOR_HEIGHT - 1, fy + rnd.nextInt(-40, 41)));
            int pad = rnd.nextInt(0, 12);
            int x0 = Math.max(0, Math.min(fx, gx) - pad);
            int y0 = Math.max(0, Math.min(fy, gy) - pad);
            int x1 = Math.min(map.width() - 1, Math.max(fx, gx) + pad);
            int y1 = Math.min(map.height() - 1, Math.max(fy, gy) + pad);
            if (danger.chargesAnyIn(x0, y0, x1, y1)) {
                continue;
            }
            LocalField field = LocalField.over(
                    steps, danger, Point.of(fx, fy), new openrsc.gamedata.geometry.Rect(x0, y0, x1, y1));
            int s = walk.steps(steps, x0, y0, x1, y1, fx, fy, gx, gy);
            int w = field.weight(gx, gy);
            String at = "(" + fx + "," + fy + ") -> (" + gx + "," + gy + ") in [" + x0 + "," + y0 + ".." + x1 + "," + y1
                    + "]";
            assertEquals(w == Integer.MAX_VALUE ? -1 : w / LocalField.STEP_COST, s, at);
            if (s >= 0) {
                assertEquals(s * LocalField.STEP_COST, field.time(gx, gy), at);
                reached++;
            }
            compared++;
        }
        assertTrue(reached > 1000, "most sampled goals are walkable: " + reached);
    }

    @Test
    @DisplayName("the window's penalty grid is penaltyAt, tile by tile")
    void penaltyGridIsPenaltyAt() {
        java.util.SplittableRandom rnd = new java.util.SplittableRandom(4242L);
        int charged = 0;
        for (DangerField danger : views) {
            for (int k = 0; k < 400; k++) {
                int w = rnd.nextInt(1, 140);
                int h = rnd.nextInt(1, 140);
                int x0 = rnd.nextInt(0, map.width() - w + 1);
                int y0 = rnd.nextInt(0, map.height() - h + 1);
                int[] grid = new int[w * h];
                danger.penaltiesInto(x0, y0, w, h, grid);
                for (int r = 0; r < h; r++) {
                    for (int c = 0; c < w; c++) {
                        int expected = danger.penaltyAt(x0 + c, y0 + r);
                        assertEquals(expected, grid[r * w + c], "(" + (x0 + c) + "," + (y0 + r) + ")");
                        if (expected != 0) {
                            charged++;
                        }
                    }
                }
            }
        }
        assertTrue(charged > 1000, "charged tiles compared: " + charged);
    }

    @Test
    @DisplayName("a charged window prices every tile as the heap walk over penaltyAt does")
    void chargedWindowMatchesTheHeap() {
        int compared = 0;
        for (DangerField danger : views) {
            int r = LocalField.MAX_RADIUS;
            for (int y = r; y + r < map.height() && compared < 60; y += 31) {
                for (int x = r; x + r < map.width() && compared < 60; x += 37) {
                    if (steps.at(x, y) == 0 || !danger.chargesAnyIn(x - r, y - r, x + r, y + r)) {
                        continue;
                    }
                    LocalField field = LocalField.build(steps, map, danger, Point.of(x, y), r);
                    int[][] heap = heapWalk(danger, x, y, r);
                    for (int ty = y - r; ty <= y + r; ty++) {
                        for (int tx = x - r; tx <= x + r; tx++) {
                            int i = (ty - (y - r)) * (2 * r + 1) + (tx - (x - r));
                            assertEquals(heap[0][i], field.weight(tx, ty), "weight at " + tx + "," + ty);
                        }
                    }
                    compared++;
                }
            }
        }
        assertTrue(compared >= 30, "charged windows compared: " + compared);
    }

    private static int[][] heapWalk(DangerField danger, int cx, int cy, int r) {
        int x0 = cx - r;
        int y0 = cy - r;
        int side = 2 * r + 1;
        int[] weight = new int[side * side];
        int[] time = new int[side * side];
        Arrays.fill(weight, Integer.MAX_VALUE);
        Arrays.fill(time, Integer.MAX_VALUE);
        java.util.PriorityQueue<long[]> open = new java.util.PriorityQueue<>((a, b) -> Long.compare(a[0], b[0]));
        int start = r * side + r;
        weight[start] = 0;
        time[start] = 0;
        open.add(new long[] {0, start});
        while (!open.isEmpty()) {
            long[] top = open.poll();
            int idx = (int) top[1];
            if (top[0] > weight[idx]) {
                continue;
            }
            int x = x0 + idx % side;
            int y = y0 + idx / side;
            for (int out = steps.at(x, y); out != 0; out &= out - 1) {
                int d = Integer.numberOfTrailingZeros(out);
                int nx = x + ClusterEdges.DX[d];
                int ny = y + ClusterEdges.DY[d];
                if (nx < x0 || ny < y0 || nx >= x0 + side || ny >= y0 + side) {
                    continue;
                }
                int n = (ny - y0) * side + (nx - x0);
                int w = weight[idx] + LocalField.STEP_COST + danger.penaltyAt(nx, ny);
                if (w < weight[n]) {
                    weight[n] = w;
                    time[n] = time[idx] + LocalField.STEP_COST;
                    open.add(new long[] {w, n});
                }
            }
        }
        return new int[][] {weight, time};
    }
}
