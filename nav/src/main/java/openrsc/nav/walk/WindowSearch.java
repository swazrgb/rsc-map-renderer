package openrsc.nav.walk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.engine.ClusterGeometry;

/**
 * A bounded A* over a {@link StepView} from the walker's tile into a target rectangle.
 *
 * <p>Every legal step, cardinal or diagonal, costs {@link #STEP_COST}, since the server moves a
 * player one tile a tick in any direction; the view's {@link StepView#extra} is added for each tile
 * entered. The search covers only a window ({@link #window}), and is guided by an exact heuristic:
 * the static step count to the target over that window.
 *
 * <p>Of equally cheap walks, one whose first step keeps the caller's {@link Momentum} wins, then the
 * one that marches deepest.
 *
 * <p>Not thread-safe: an instance caches the heuristic of its last window and target.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/WalkingQueue.java#L79">WalkingQueue.processNextMovement</a>
 */
public final class WindowSearch {

    /** One step's cost in deci-ticks: a tick, whatever its direction. */
    public static final int STEP_COST = 10;

    /**
     * What a tile a body stands on costs when bodies are not walls: enough that any way round wins,
     * so only a sealed way is priced through.
     */
    public static final int OCCUPIED_COST = 1000;

    static final int MAX_WINDOW = 9 * ClusterGeometry.CLUSTER_SIZE * ClusterGeometry.CLUSTER_SIZE;

    /** Tiles of slack {@link #localRadius} adds to the goal's distance, room to detour. */
    public static final int LOCAL_PAD = 16;

    public static final int LOCAL_RADIUS_MIN = 24;
    public static final int LOCAL_RADIUS_MAX = 48;

    /**
     * Half-side, in tiles, of the square a walk with no corridor is searched over: the Chebyshev
     * distance from {@code from} to {@code goal} plus {@link #LOCAL_PAD}, held within
     * {@link #LOCAL_RADIUS_MIN} and {@link #LOCAL_RADIUS_MAX}; -1 when the goal is further away than
     * {@link #LOCAL_RADIUS_MAX}.
     */
    public static int localRadius(Point from, Point goal) {
        int d = Point.chebyshev(from.x(), from.y(), goal.x(), goal.y());
        if (d + LOCAL_PAD > LOCAL_RADIUS_MAX) {
            return d <= LOCAL_RADIUS_MAX ? LOCAL_RADIUS_MAX : -1;
        }
        return Math.max(LOCAL_RADIUS_MIN, d + LOCAL_PAD);
    }

    /** The direction of a walk's first step, each component -1, 0 or 1, or {@link #NONE}. */
    public record Momentum(int dx, int dy) {

        public static final Momentum NONE = new Momentum(Integer.MIN_VALUE, Integer.MIN_VALUE);

        public static Momentum of(Point from, Point to) {
            return new Momentum(Integer.signum(to.x() - from.x()), Integer.signum(to.y() - from.y()));
        }
    }

    private final CollisionMap map;

    private int[] hField;

    private int hXMin;
    private int hYMin;
    private int hWidth;
    private int hHeight;
    private Rect hTarget;
    private int[] hQueue;

    public WindowSearch(CollisionMap map) {
        this.map = map;
    }

    /**
     * The walk from {@code pos} into {@code target}, starting on {@code pos}; null when the window is
     * too large or holds no way in. {@code corridor} widens the window ({@link #window}).
     * {@code onward} adds a target tile's Chebyshev steps to it, so a seam is crossed where the way on
     * is cheapest. With {@code occupancyHard} an occupied tile is a wall, else it costs
     * {@link #OCCUPIED_COST}; a target tile is never occupied. The walk is {@link #pulled} straight.
     */
    public @Nullable List<Point> run(
            StepView view,
            Point pos,
            Rect target,
            @Nullable Rect corridor,
            @Nullable Rect onward,
            boolean occupancyHard,
            Momentum momentum) {
        Rect box = window(map, pos, target, corridor);
        if (box == null) {
            return null;
        }
        int xMin = box.minX();
        int yMin = box.minY();
        int xMax = box.maxX();
        int yMax = box.maxY();
        int width = xMax - xMin + 1;
        int height = yMax - yMin + 1;
        ensureHField(target, xMin, yMin, width, height);
        int[] h = hField;
        int start = (pos.y() - yMin) * width + (pos.x() - xMin);
        if (h[start] == Integer.MAX_VALUE) {
            // The view may open what the static map has closed (a door standing open): fall back to a
            // Chebyshev bound, which stays admissible.
            h = new int[width * height];
            for (int i = 0; i < h.length; i++) {
                int hx = xMin + i % width;
                int hy = yMin + i / width;
                int tx = Math.clamp(hx, target.minX(), target.maxX());
                int ty = Math.clamp(hy, target.minY(), target.maxY());
                h[i] = STEP_COST * Math.max(Math.abs(hx - tx), Math.abs(hy - ty));
            }
        }
        // dist holds (cost << 1) | turned: the momentum bit orders only walks of equal cost.
        int[] dist = new int[width * height];
        int[] parent = new int[width * height];
        Arrays.fill(dist, Integer.MAX_VALUE);
        Arrays.fill(parent, -1);
        dist[start] = 0;
        long[] heap = new long[1024];
        int heapSize = 0;
        heap[heapSize++] = packTie(h[start], 0, 0, start);
        int found = -1;
        int bestArrival = Integer.MAX_VALUE;
        while (heapSize > 0) {
            long top = heap[0];
            int cur = (int) (top & NODE_MASK);
            int f = (int) (top >>> 40);
            if ((f << 1) >= bestArrival) {
                break;
            }
            heapSize--;
            if (heapSize > 0) {
                heap[0] = heap[heapSize];
                siftDown(heap, heapSize, 0);
            }
            if (f - h[cur] > (dist[cur] >>> 1)) {
                continue;
            }
            int cx = xMin + cur % width;
            int cy = yMin + cur / width;
            if (target.contains(cx, cy)) {
                int arrival = (((dist[cur] >>> 1) + costToGo(cx, cy, onward)) << 1) | (dist[cur] & 1);
                if (arrival < bestArrival) {
                    bestArrival = arrival;
                    found = cur;
                }
                if (onward == null) {
                    break;
                }
                // With a way on to price, the crossing may be cheaper further along the span: walk on.
            }
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if (nx < xMin || ny < yMin || nx > xMax || ny > yMax) {
                        continue;
                    }
                    int nIdx = (ny - yMin) * width + (nx - xMin);
                    boolean occupied = view.occupied(nx, ny) && !target.contains(nx, ny);
                    if (occupied && occupancyHard) {
                        continue;
                    }
                    int occupancyCost = occupied ? OCCUPIED_COST : 0;
                    if (!view.canStep(cx, cy, nx, ny)) {
                        continue;
                    }
                    if (h[nIdx] == Integer.MAX_VALUE) {
                        continue;
                    }
                    int nd = (dist[cur] >>> 1) + STEP_COST + occupancyCost + view.extra(nx, ny);
                    int nTurned = cur == start
                            ? (momentum.dx() != Integer.MIN_VALUE && (dx != momentum.dx() || dy != momentum.dy())
                                    ? 1
                                    : 0)
                            : dist[cur] & 1;
                    int nKey = (nd << 1) | nTurned;
                    if (nKey < dist[nIdx]) {
                        dist[nIdx] = nKey;
                        parent[nIdx] = cur;
                        if (heapSize >= heap.length) {
                            heap = Arrays.copyOf(heap, heap.length * 2);
                        }
                        heap[heapSize++] = packTie(nd + h[nIdx], nTurned, nd, nIdx);
                        siftUp(heap, heapSize - 1);
                    }
                }
            }
        }
        if (found < 0) {
            return null;
        }
        List<Point> path = new ArrayList<>();
        for (int cur = found; cur >= 0; cur = parent[cur]) {
            path.add(Point.of(xMin + cur % width, yMin + cur / width));
        }
        Collections.reverse(path);
        return pulled(path, view, target, occupancyHard);
    }

    static List<Point> pulled(List<Point> path, StepView view, Rect target, boolean occupancyHard) {
        return pulled(path, view, target, occupancyHard, Integer.MAX_VALUE);
    }

    /**
     * {@code path} with each stretch replaced by the straight line to the furthest later tile whose
     * line is legal in {@code view} and costs no more than the path's own way there, so the result is
     * never longer or dearer. Still one tile per step; no line spans more than {@code reach} tiles of
     * the path.
     */
    public static List<Point> pulled(List<Point> path, StepView view, Rect target, boolean occupancyHard, int reach) {
        int n = path.size();
        if (n < 3) {
            return path;
        }
        int[] own = new int[n];
        for (int k = 1; k < n; k++) {
            own[k] = own[k - 1] + STEP_COST + charge(view, target, path.get(k));
        }
        List<Point> out = new ArrayList<>(n);
        out.add(path.get(0));
        int i = 0;
        while (i < n - 1) {
            Point from = path.get(i);
            // Furthest first: a nearer line can hit a wall that a longer one clears.
            int end = i + 1;
            List<Point> line = null;
            for (int j = (int) Math.min(n - 1, (long) i + reach); j > i + 1; j--) {
                List<Point> straight = line(from, path.get(j), view, target, occupancyHard);
                if (straight == null) {
                    continue;
                }
                int cost = 0;
                for (Point p : straight) {
                    cost += STEP_COST + charge(view, target, p);
                }
                if (cost <= own[j] - own[i]) {
                    end = j;
                    line = straight;
                    break;
                }
            }
            if (line == null) {
                out.add(path.get(i + 1));
            } else {
                out.addAll(line);
            }
            i = end;
        }
        return out;
    }

    private static int charge(StepView view, Rect target, Point p) {
        boolean occupied = view.occupied(p.x(), p.y()) && !target.contains(p.x(), p.y());
        return view.extra(p.x(), p.y()) + (occupied ? OCCUPIED_COST : 0);
    }

    /**
     * The tiles of the straight line from {@code a} (excluded) to {@code b}, one step apart; null when
     * a step is illegal in {@code view}, or, with {@code occupancyHard}, a tile off the target is
     * occupied.
     */
    static @Nullable List<Point> line(Point a, Point b, StepView view, Rect target, boolean occupancyHard) {
        int dx = b.x() - a.x();
        int dy = b.y() - a.y();
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        List<Point> out = new ArrayList<>(steps);
        int px = a.x();
        int py = a.y();
        for (int k = 1; k <= steps; k++) {
            int x = a.x() + (int) Math.round((double) dx * k / steps);
            int y = a.y() + (int) Math.round((double) dy * k / steps);
            if (!view.canStep(px, py, x, y)) {
                return null;
            }
            if (occupancyHard && view.occupied(x, y) && !target.contains(x, y)) {
                return null;
            }
            out.add(Point.of(x, y));
            px = x;
            py = y;
        }
        return out;
    }

    /**
     * The tiles a walk from {@code pos} into {@code target} is searched over, or null when they would
     * outnumber nine clusters: the clusters of {@code pos} and the target, widened to the clusters
     * {@code corridor}'s corners lie in or, without a corridor, to the {@link #localRadius} square
     * round {@code pos} when the target is near enough to have one.
     */
    public static @Nullable Rect window(CollisionMap map, Point pos, Rect target, @Nullable Rect corridor) {
        int c1 = ClusterGeometry.clusterOf(pos.x(), pos.y());
        int c2 = ClusterGeometry.clusterOf(Math.clamp(target.minX(), 0, CollisionMap.WIDTH - 1), target.minY());
        Rect w1 = clusterBounds(c1);
        Rect w2 = clusterBounds(c2);
        int xMin = Math.min(w1.minX(), w2.minX());
        int xMax = Math.max(w1.maxX(), w2.maxX());
        int yMin = Math.min(w1.minY(), w2.minY());
        int yMax = Math.max(w1.maxY(), w2.maxY());
        if (corridor != null) {
            Rect lo = clusterBounds(ClusterGeometry.clusterOf(corridor.minX(), corridor.minY()));
            Rect hi = clusterBounds(ClusterGeometry.clusterOf(corridor.maxX(), corridor.maxY()));
            xMin = Math.min(xMin, lo.minX());
            xMax = Math.max(xMax, hi.maxX());
            yMin = Math.min(yMin, lo.minY());
            yMax = Math.max(yMax, hi.maxY());
        } else {
            Point far = Point.chebyshev(pos.x(), pos.y(), target.minX(), target.minY())
                            >= Point.chebyshev(pos.x(), pos.y(), target.maxX(), target.maxY())
                    ? Point.of(target.minX(), target.minY())
                    : Point.of(target.maxX(), target.maxY());
            int r = localRadius(pos, far);
            if (r > 0) {
                xMin = Math.max(0, Math.min(xMin, pos.x() - r));
                xMax = Math.min(CollisionMap.WIDTH - 1, Math.max(xMax, pos.x() + r));
                yMin = Math.max(0, Math.min(yMin, pos.y() - r));
                yMax = Math.min(map.height() - 1, Math.max(yMax, pos.y() + r));
            }
        }
        int width = xMax - xMin + 1;
        int height = yMax - yMin + 1;
        if (width <= 0 || height <= 0 || (long) width * height > MAX_WINDOW) {
            return null;
        }
        return new Rect(xMin, yMin, xMax, yMax);
    }

    private static int costToGo(int x, int y, @Nullable Rect onward) {
        if (onward == null) {
            return 0;
        }
        int ox = Math.clamp(x, onward.minX(), onward.maxX());
        int oy = Math.clamp(y, onward.minY(), onward.maxY());
        return STEP_COST * Math.max(Math.abs(x - ox), Math.abs(y - oy));
    }

    private void ensureHField(Rect target, int xMin, int yMin, int width, int height) {
        if (hField != null
                && hXMin == xMin
                && hYMin == yMin
                && hWidth == width
                && hHeight == height
                && target.equals(hTarget)) {
            return;
        }
        int n = width * height;
        int[] h = hField != null && hField.length == n ? hField : new int[n];
        Arrays.fill(h, 0, n, Integer.MAX_VALUE);
        if (hQueue == null || hQueue.length < n) {
            hQueue = new int[n];
        }
        // Every step costs the same, so a breadth-first flood settles each tile on first discovery.
        int[] queue = hQueue;
        int head = 0;
        int tail = 0;
        int xMax = xMin + width - 1;
        int yMax = yMin + height - 1;
        for (int ty = Math.max(target.minY(), yMin); ty <= Math.min(target.maxY(), yMax); ty++) {
            for (int tx = Math.max(target.minX(), xMin); tx <= Math.min(target.maxX(), xMax); tx++) {
                int idx = (ty - yMin) * width + (tx - xMin);
                h[idx] = 0;
                queue[tail++] = idx;
            }
        }
        while (head < tail) {
            int cur = queue[head++];
            int cx = xMin + cur % width;
            int cy = yMin + cur / width;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dy == 0) {
                        continue;
                    }
                    int nx = cx + dx;
                    int ny = cy + dy;
                    if (nx < xMin || ny < yMin || nx > xMax || ny > yMax) {
                        continue;
                    }
                    // Toward the target, as a walker steps: a one-way wall counts the right way round.
                    if (!map.canStep(nx, ny, cx, cy, Overlays.NONE)) {
                        continue;
                    }
                    int nIdx = (ny - yMin) * width + (nx - xMin);
                    if (h[nIdx] == Integer.MAX_VALUE) {
                        h[nIdx] = h[cur] + STEP_COST;
                        queue[tail++] = nIdx;
                    }
                }
            }
        }
        hField = h;
        hXMin = xMin;
        hYMin = yMin;
        hWidth = width;
        hHeight = height;
        hTarget = target;
    }

    static Rect clusterBounds(int cluster) {
        return new Rect(
                ClusterGeometry.minX(cluster),
                ClusterGeometry.minY(cluster),
                ClusterGeometry.maxX(cluster) - 1,
                ClusterGeometry.maxY(cluster) - 1);
    }

    /**
     * Heap key: f, then the momentum bit, then inverted g, then the node, so of equal f an unturned
     * walk pops first, then the deepest. The node takes 23 bits, enough for {@link #MAX_WINDOW}.
     */
    private static long packTie(int f, int turned, int g, int id) {
        return ((long) f << 40)
                | ((long) turned << 39)
                | ((long) (0xFFFF - Math.min(g, 0xFFFF)) << 23)
                | (id & NODE_MASK);
    }

    private static final int NODE_MASK = 0x7FFFFF;

    private static void siftUp(long[] heap, int i) {
        long key = heap[i];
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heap[p] <= key) {
                break;
            }
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = key;
    }

    private static void siftDown(long[] heap, int n, int i) {
        long key = heap[i];
        int half = n >>> 1;
        while (i < half) {
            int l = (i << 1) + 1;
            int r = l + 1;
            int smallest = l;
            if (r < n && heap[r] < heap[l]) {
                smallest = r;
            }
            if (heap[smallest] >= key) {
                break;
            }
            heap[i] = heap[smallest];
            i = smallest;
        }
        heap[i] = key;
    }
}
