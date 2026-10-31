package openrsc.nav.engine;

import java.util.Arrays;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.walk.WindowSearch;

/**
 * The exact walk to every tile of a window around a start: one Dijkstra over {@link StepMasks}, a
 * step weighing {@link #STEP_COST} plus the danger field's charge on the tile it enters, and taking
 * {@link #STEP_COST} of time whatever it is charged. Walks only: a transport is a wall here, so what
 * the field reaches it prices exactly, and what it cannot is the hierarchy's to price. The window is
 * the walker's own local one ({@link WindowSearch#localRadius}). Immutable once built.
 */
final class LocalField {

    static final int STEP_COST = Legs.STEP_COST;

    static final int MAX_RADIUS = WindowSearch.LOCAL_RADIUS_MAX;

    private final int cx;
    private final int cy;
    private final int radius;
    private final int x0;
    private final int y0;
    private final int width;
    private final int height;
    private final int[] weight;
    private final int[] time;
    /** The view the field was charged under; compared by identity. */
    final @Nullable DangerField danger;

    private boolean flat = true;

    private LocalField(int cx, int cy, int radius, @Nullable DangerField danger, int x0, int y0, int x1, int y1) {
        this.cx = cx;
        this.cy = cy;
        this.radius = radius;
        this.danger = danger;
        this.x0 = x0;
        this.y0 = y0;
        this.width = x1 - x0 + 1;
        this.height = y1 - y0 + 1;
        this.weight = new int[width * height];
        this.time = new int[width * height];
    }

    /** The half-side a window around {@code from} needs to price {@code goal}, or -1 when no window reaches it. */
    static int radiusFor(Point from, Point goal) {
        return WindowSearch.localRadius(from, goal);
    }

    /**
     * Whether this field answers the uncharged walk from {@code from} for a window of {@code radius}:
     * built there, at least that wide, and charged nothing it reached.
     */
    boolean servesPlain(Point from, int radius) {
        return flat && this.radius >= 0 && cx == from.x() && cy == from.y() && this.radius >= radius;
    }

    boolean serves(Point from, @Nullable DangerField danger, int radius) {
        return this.radius >= 0 && cx == from.x() && cy == from.y() && this.danger == danger && this.radius >= radius;
    }

    /** The field around {@code from}: a square of half-side {@code radius}, clipped to the map. */
    static LocalField build(StepMasks steps, CollisionMap map, @Nullable DangerField danger, Point from, int radius) {
        LocalField f = new LocalField(
                from.x(),
                from.y(),
                radius,
                danger,
                Math.max(0, from.x() - radius),
                Math.max(0, from.y() - radius),
                Math.min(map.width() - 1, from.x() + radius),
                Math.min(map.height() - 1, from.y() + radius));
        f.run(steps);
        return f;
    }

    /**
     * The field over {@code box}: the window the walker searches for a walk it is handed a corridor for
     * ({@link WindowSearch#window}). Never a {@link #serves} candidate.
     */
    static LocalField over(StepMasks steps, @Nullable DangerField danger, Point from, Rect box) {
        LocalField f = new LocalField(from.x(), from.y(), -1, danger, box.minX(), box.minY(), box.maxX(), box.maxY());
        f.run(steps);
        return f;
    }

    private void run(StepMasks steps) {
        Arrays.fill(weight, Integer.MAX_VALUE);
        Arrays.fill(time, Integer.MAX_VALUE);
        // A window charging no tile is a uniform-cost walk, which a breadth-first fill settles exactly.
        boolean charged = danger != null && danger.chargesAnyIn(x0, y0, x0 + width - 1, y0 + height - 1);
        if (!charged) {
            runFlat(steps);
            return;
        }
        int n = width * height;
        int[] penalty = new int[n];
        danger.penaltiesInto(x0, y0, width, height, penalty);
        long[] heap = new long[Math.max(16, n / 4)];
        int heapSize = 0;
        int start = index(cx, cy);
        weight[start] = 0;
        time[start] = 0;
        heap[heapSize++] = key(0, start);
        while (heapSize > 0) {
            long top = heap[0];
            int g = (int) (top >>> 24);
            int idx = (int) (top & 0xFFFFFF);
            heapSize--;
            if (heapSize > 0) {
                heap[0] = heap[heapSize];
                siftDown(heap, heapSize, 0);
            }
            if (g > weight[idx]) {
                continue;
            }
            int x = x0 + idx % width;
            int y = y0 + idx / width;
            int t = time[idx] + STEP_COST;
            for (int out = steps.at(x, y); out != 0; out &= out - 1) {
                int d = Integer.numberOfTrailingZeros(out);
                int nx = x + ClusterEdges.DX[d];
                int ny = y + ClusterEdges.DY[d];
                if (nx < x0 || ny < y0 || nx >= x0 + width || ny >= y0 + height) {
                    continue;
                }
                int nIdx = index(nx, ny);
                int charge = penalty[nIdx];
                flat &= charge == 0;
                int w = g + STEP_COST + charge;
                if (w < weight[nIdx]) {
                    weight[nIdx] = w;
                    time[nIdx] = t;
                    if (heapSize == heap.length) {
                        heap = Arrays.copyOf(heap, heap.length * 2);
                    }
                    heap[heapSize] = key(w, nIdx);
                    siftUp(heap, heapSize);
                    heapSize++;
                }
            }
        }
    }

    private void runFlat(StepMasks steps) {
        int[] queue = new int[width * height];
        int head = 0;
        int tail = 0;
        int start = index(cx, cy);
        weight[start] = 0;
        time[start] = 0;
        queue[tail++] = start;
        while (head < tail) {
            int idx = queue[head++];
            int x = x0 + idx % width;
            int y = y0 + idx / width;
            int t = time[idx] + STEP_COST;
            for (int out = steps.at(x, y); out != 0; out &= out - 1) {
                int d = Integer.numberOfTrailingZeros(out);
                int nx = x + ClusterEdges.DX[d];
                int ny = y + ClusterEdges.DY[d];
                if (nx < x0 || ny < y0 || nx >= x0 + width || ny >= y0 + height) {
                    continue;
                }
                int nIdx = index(nx, ny);
                if (weight[nIdx] == Integer.MAX_VALUE) {
                    weight[nIdx] = t;
                    time[nIdx] = t;
                    queue[tail++] = nIdx;
                }
            }
        }
    }

    boolean covers(int x, int y) {
        return x >= x0 && y >= y0 && x < x0 + width && y < y0 + height;
    }

    /** The walk's weight to {@code (x, y)}, danger charged; {@link Integer#MAX_VALUE} where the window has none. */
    int weight(int x, int y) {
        return covers(x, y) ? weight[index(x, y)] : Integer.MAX_VALUE;
    }

    /** That walk's time, in deci-ticks; meaningful only where {@link #weight} is finite. */
    int time(int x, int y) {
        return covers(x, y) ? time[index(x, y)] : Integer.MAX_VALUE;
    }

    private int index(int x, int y) {
        return (y - y0) * width + (x - x0);
    }

    private static long key(int weight, int idx) {
        return ((long) weight << 24) | idx;
    }

    private static void siftUp(long[] heap, int i) {
        long v = heap[i];
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (heap[p] <= v) {
                break;
            }
            heap[i] = heap[p];
            i = p;
        }
        heap[i] = v;
    }

    private static void siftDown(long[] heap, int size, int i) {
        long v = heap[i];
        while (true) {
            int l = 2 * i + 1;
            if (l >= size) {
                break;
            }
            int r = l + 1;
            int c = r < size && heap[r] < heap[l] ? r : l;
            if (heap[c] >= v) {
                break;
            }
            heap[i] = heap[c];
            i = c;
        }
        heap[i] = v;
    }
}
