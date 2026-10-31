package openrsc.nav.engine;

import java.util.Arrays;

/**
 * A* over {@link StepMasks} inside a window, one walk at a time: what a {@link LocalField} around
 * the start would answer for one goal, without settling the rest of the window. The bound is the
 * Chebyshev distance, exact on open ground since a diagonal is one step. A walk is at most 65535
 * steps and a window under 2^24 tiles. Holds its scratch: one per thread.
 */
final class FlatWalk {

    private int[] dist = new int[0];
    private int[] seen = new int[0];
    private int[] hops = new int[0];

    private int generation;
    private long[] heap = new long[256];

    /** Steps from {@code (sx, sy)} to {@code (gx, gy)}, both in the window, without leaving it; -1 when none. */
    int steps(StepMasks steps, int x0, int y0, int x1, int y1, int sx, int sy, int gx, int gy) {
        int width = x1 - x0 + 1;
        int n = width * (y1 - y0 + 1);
        if (dist.length < n) {
            dist = new int[n];
            seen = new int[n];
            generation = 0;
        }
        if (++generation == Integer.MAX_VALUE) {
            Arrays.fill(seen, 0);
            generation = 1;
        }
        int start = (sy - y0) * width + (sx - x0);
        int goal = (gy - y0) * width + (gx - x0);
        int size = 0;
        dist[start] = 0;
        seen[start] = generation;
        heap[size++] = key(chebyshev(sx, sy, gx, gy), 0, start);
        while (size > 0) {
            long top = heap[0];
            size--;
            if (size > 0) {
                heap[0] = heap[size];
                siftDown(size);
            }
            int idx = (int) (top & 0xFFFFFF);
            int g = 0xFFFF - (int) ((top >>> 24) & 0xFFFF);
            if (g > dist[idx]) {
                continue;
            }
            if (idx == goal) {
                return g;
            }
            int x = x0 + idx % width;
            int y = y0 + idx / width;
            for (int out = steps.at(x, y); out != 0; out &= out - 1) {
                int d = Integer.numberOfTrailingZeros(out);
                int nx = x + ClusterEdges.DX[d];
                int ny = y + ClusterEdges.DY[d];
                if (nx < x0 || ny < y0 || nx > x1 || ny > y1) {
                    continue;
                }
                int nIdx = (ny - y0) * width + (nx - x0);
                if (seen[nIdx] == generation && dist[nIdx] <= g + 1) {
                    continue;
                }
                seen[nIdx] = generation;
                dist[nIdx] = g + 1;
                if (size == heap.length) {
                    heap = Arrays.copyOf(heap, size * 2);
                }
                heap[size] = key(g + 1 + chebyshev(nx, ny, gx, gy), g + 1, nIdx);
                siftUp(size++);
            }
        }
        return -1;
    }

    /** The tile {@link #stepsInto} stopped on, as {@code y * 65536 + x}; -1 when it found none. */
    int reached = -1;

    /**
     * Steps from {@code (sx, sy)} to the nearest tile of {@code [rx0..rx1] x [ry0..ry1]} without leaving
     * the window, or -1; the tile is {@link #reached}.
     */
    int stepsInto(StepMasks steps, int x0, int y0, int x1, int y1, int sx, int sy, int rx0, int ry0, int rx1, int ry1) {
        reached = -1;
        int width = x1 - x0 + 1;
        int n = width * (y1 - y0 + 1);
        if (dist.length < n) {
            dist = new int[n];
            seen = new int[n];
            generation = 0;
        }
        if (++generation == Integer.MAX_VALUE) {
            Arrays.fill(seen, 0);
            generation = 1;
        }
        int start = (sy - y0) * width + (sx - x0);
        int size = 0;
        dist[start] = 0;
        seen[start] = generation;
        heap[size++] = key(toRect(sx, sy, rx0, ry0, rx1, ry1), 0, start);
        while (size > 0) {
            long top = heap[0];
            size--;
            if (size > 0) {
                heap[0] = heap[size];
                siftDown(size);
            }
            int idx = (int) (top & 0xFFFFFF);
            int g = 0xFFFF - (int) ((top >>> 24) & 0xFFFF);
            if (g > dist[idx]) {
                continue;
            }
            int x = x0 + idx % width;
            int y = y0 + idx / width;
            if (x >= rx0 && x <= rx1 && y >= ry0 && y <= ry1) {
                reached = y * 65536 + x;
                return g;
            }
            for (int out = steps.at(x, y); out != 0; out &= out - 1) {
                int d = Integer.numberOfTrailingZeros(out);
                int nx = x + ClusterEdges.DX[d];
                int ny = y + ClusterEdges.DY[d];
                if (nx < x0 || ny < y0 || nx > x1 || ny > y1) {
                    continue;
                }
                int nIdx = (ny - y0) * width + (nx - x0);
                if (seen[nIdx] == generation && dist[nIdx] <= g + 1) {
                    continue;
                }
                seen[nIdx] = generation;
                dist[nIdx] = g + 1;
                if (size == heap.length) {
                    heap = Arrays.copyOf(heap, size * 2);
                }
                heap[size] = key(g + 1 + toRect(nx, ny, rx0, ry0, rx1, ry1), g + 1, nIdx);
                siftUp(size++);
            }
        }
        return -1;
    }

    /**
     * The lightest walk from {@code (sx, sy)} into the rectangle without leaving the window, a step
     * weighing {@link Legs#STEP_COST} plus {@code danger}'s charge on the tile it enters, or -1 when none
     * weighs at most {@code bound}. Nothing heavier than {@code bound} is queued, so a tight bound
     * settles little. The walk's step count is {@link #reachedSteps}.
     */
    long weightInto(
            StepMasks steps,
            DangerField danger,
            int x0,
            int y0,
            int x1,
            int y1,
            int sx,
            int sy,
            int rx0,
            int ry0,
            int rx1,
            int ry1,
            long bound) {
        reachedSteps = -1;
        int width = x1 - x0 + 1;
        int n = width * (y1 - y0 + 1);
        if (dist.length < n) {
            dist = new int[n];
            seen = new int[n];
            generation = 0;
        }
        if (hops.length < n) {
            hops = new int[n];
        }
        if (++generation == Integer.MAX_VALUE) {
            Arrays.fill(seen, 0);
            generation = 1;
        }
        int start = (sy - y0) * width + (sx - x0);
        int size = 0;
        dist[start] = 0;
        hops[start] = 0;
        seen[start] = generation;
        heap[size++] = weightKey((long) Legs.STEP_COST * toRect(sx, sy, rx0, ry0, rx1, ry1), start);
        while (size > 0) {
            long top = heap[0];
            size--;
            if (size > 0) {
                heap[0] = heap[size];
                siftDown(size);
            }
            int idx = (int) (top & 0xFFFFFF);
            int g = dist[idx];
            int x = x0 + idx % width;
            int y = y0 + idx / width;
            // A stale entry: the tile was reached lighter since.
            if ((top >>> 24) != g + (long) Legs.STEP_COST * toRect(x, y, rx0, ry0, rx1, ry1)) {
                continue;
            }
            if (x >= rx0 && x <= rx1 && y >= ry0 && y <= ry1) {
                reachedSteps = hops[idx];
                return g;
            }
            for (int out = steps.at(x, y); out != 0; out &= out - 1) {
                int d = Integer.numberOfTrailingZeros(out);
                int nx = x + ClusterEdges.DX[d];
                int ny = y + ClusterEdges.DY[d];
                if (nx < x0 || ny < y0 || nx > x1 || ny > y1) {
                    continue;
                }
                int nIdx = (ny - y0) * width + (nx - x0);
                long w = (long) g + Legs.STEP_COST + danger.penaltyAt(nx, ny);
                long f = w + (long) Legs.STEP_COST * toRect(nx, ny, rx0, ry0, rx1, ry1);
                if (f > bound || (seen[nIdx] == generation && dist[nIdx] <= w)) {
                    continue;
                }
                seen[nIdx] = generation;
                dist[nIdx] = (int) w;
                hops[nIdx] = hops[idx] + 1;
                if (size == heap.length) {
                    heap = Arrays.copyOf(heap, size * 2);
                }
                heap[size] = weightKey(f, nIdx);
                siftUp(size++);
            }
        }
        return -1;
    }

    /** The step count of the walk {@link #weightInto} found; -1 when it found none. */
    int reachedSteps = -1;

    private static long weightKey(long f, int idx) {
        return (f << 24) | idx;
    }

    private static int toRect(int x, int y, int rx0, int ry0, int rx1, int ry1) {
        int dx = x < rx0 ? rx0 - x : x > rx1 ? x - rx1 : 0;
        int dy = y < ry0 ? ry0 - y : y > ry1 ? y - ry1 : 0;
        return Math.max(dx, dy);
    }

    private static int chebyshev(int ax, int ay, int bx, int by) {
        return Math.max(Math.abs(ax - bx), Math.abs(ay - by));
    }

    /** f, then g inverted so that equal f pops the deepest first, then the cell. */
    private static long key(int f, int g, int idx) {
        return ((long) f << 40) | ((long) (0xFFFF - g) << 24) | idx;
    }

    private void siftUp(int i) {
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

    private void siftDown(int size) {
        long v = heap[0];
        int i = 0;
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
