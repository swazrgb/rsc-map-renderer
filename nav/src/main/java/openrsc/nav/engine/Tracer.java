package openrsc.nav.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Planner;
import openrsc.nav.Route;
import openrsc.nav.TracedRoute;
import openrsc.nav.walk.StepView;
import openrsc.nav.walk.WindowSearch;

/**
 * A route's steps laid out as the tiles a plain walk through them crosses, for drawing. Every stretch
 * between two steps lies in one cluster, so each is a breadth-first search over that cluster's
 * {@link ClusterEdges}; no leg plane is read, so drawing builds nothing the planner keeps. No danger
 * is charged: the route's seams, hops and stops are the engine's choice, and between them this draws
 * the shortest walk. A stretch the search cannot make is drawn straight and marked a jump. Each
 * unbroken run is pulled straight as the walker pulls its own path ({@link WindowSearch#pulled}).
 * One per trace: it holds the search's scratch.
 */
final class Tracer {

    /** The most tiles one trace lays out; a longer route is cut short. */
    private static final int MAX_TILES = 20_000;

    /** The longest line a run is pulled straight across: past the walker's own window, and bounded. */
    private static final int PULL_REACH = 64;

    private static final int CS = ClusterGeometry.CLUSTER_SIZE;
    private static final int CELLS = CS * CS;
    private static final int[] CARDINALS = {1, 3, 5, 7};

    private final CollisionMap map;
    private final ClusterGraph graph;
    private final StepView plain;

    private final int[] parent = new int[CELLS];
    private final int[] seen = new int[CELLS];
    private final int[] goal = new int[CELLS];
    private final int[] queue = new int[CELLS];
    /** Per goal cell of a seam, the far-side tile its crossing step lands on. */
    private final int[] across = new int[CELLS];

    private int generation;

    private int[] xs = new int[256];
    private int[] ys = new int[256];
    private boolean[] jump = new boolean[256];
    private int size;
    private int x;
    private int y;

    Tracer(CollisionMap map, ClusterGraph graph) {
        this.map = map;
        this.graph = graph;
        this.plain = StepView.ofMap(map, Planner.DangerCharge.NONE);
    }

    TracedRoute trace(Point from, Route route) {
        x = from.x();
        y = from.y();
        add(x, y, false);
        for (Route.Step step : route.steps()) {
            if (size >= MAX_TILES) {
                break;
            }
            switch (step) {
                case Route.Cross c -> cross(c.span());
                case Route.Hop h -> {
                    walkTo(h.entry());
                    x = h.landing().x();
                    y = h.landing().y();
                    add(x, y, true);
                }
                case Route.BankStop b -> walkTo(b.bank());
                case Route.Arrive a -> walkInto(a.target());
            }
        }
        return pulledStraight();
    }

    private TracedRoute pulledStraight() {
        int[] ox = new int[size];
        int[] oy = new int[size];
        boolean[] oj = new boolean[size];
        int out = 0;
        int start = 0;
        while (start < size) {
            int end = start + 1;
            while (end < size && !jump[end]) {
                end++;
            }
            List<Point> run = new ArrayList<>(end - start);
            for (int i = start; i < end; i++) {
                run.add(Point.of(xs[i], ys[i]));
            }
            Point last = run.getLast();
            List<Point> walked = WindowSearch.pulled(run, plain, Rect.of(last, last), false, PULL_REACH);
            for (int i = 0; i < walked.size(); i++) {
                ox[out] = walked.get(i).x();
                oy[out] = walked.get(i).y();
                oj[out] = i == 0 && jump[start];
                out++;
            }
            start = end;
        }
        return new TracedRoute(Arrays.copyOf(ox, out), Arrays.copyOf(oy, out), Arrays.copyOf(oj, out));
    }

    /** To a tile one cardinal step from {@code span}, the far side's entrance, and the step onto it. */
    private void cross(Rect span) {
        if (span.contains(x, y)) {
            return;
        }
        int cluster = ClusterGeometry.clusterOf(x, y);
        int xMin = ClusterGeometry.minX(cluster);
        int yMin = ClusterGeometry.minY(cluster);
        int stamp = nextGeneration();
        boolean any = false;
        for (int fy = span.minY(); fy <= span.maxY(); fy++) {
            for (int fx = span.minX(); fx <= span.maxX(); fx++) {
                for (int d : CARDINALS) {
                    int nx = fx - ClusterEdges.DX[d];
                    int ny = fy - ClusterEdges.DY[d];
                    int l = local(nx, ny, xMin, yMin);
                    if (l >= 0 && ClusterEdges.canStep(map, nx, ny, fx, fy)) {
                        goal[l] = stamp;
                        across[l] = fy * CollisionMap.WIDTH + fx;
                        any = true;
                    }
                }
            }
        }
        int end = any ? search(cluster, xMin, yMin, stamp) : -1;
        if (end < 0) {
            straightTo((span.minX() + span.maxX()) / 2, (span.minY() + span.maxY()) / 2);
            return;
        }
        x = across[end] % CollisionMap.WIDTH;
        y = across[end] / CollisionMap.WIDTH;
        add(x, y, false);
    }

    private void walkTo(Point p) {
        if (p.x() == x && p.y() == y) {
            return;
        }
        int cluster = ClusterGeometry.clusterOf(x, y);
        int xMin = ClusterGeometry.minX(cluster);
        int yMin = ClusterGeometry.minY(cluster);
        int l = local(p.x(), p.y(), xMin, yMin);
        int stamp = nextGeneration();
        if (l < 0) {
            straightTo(p.x(), p.y());
            return;
        }
        goal[l] = stamp;
        if (search(cluster, xMin, yMin, stamp) < 0) {
            straightTo(p.x(), p.y());
        }
    }

    private void walkInto(Area area) {
        if (area.contains(x, y)) {
            return;
        }
        int cluster = ClusterGeometry.clusterOf(x, y);
        int xMin = ClusterGeometry.minX(cluster);
        int yMin = ClusterGeometry.minY(cluster);
        int stamp = nextGeneration();
        boolean any = false;
        int x0 = Math.max(area.minX(), xMin);
        int x1 = Math.min(area.maxX(), xMin + CS - 1);
        int y0 = Math.max(area.minY(), yMin);
        int y1 = Math.min(area.maxY(), yMin + CS - 1);
        for (int gy = y0; gy <= y1; gy++) {
            for (int gx = x0; gx <= x1; gx++) {
                if (area.contains(gx, gy)) {
                    goal[local(gx, gy, xMin, yMin)] = stamp;
                    any = true;
                }
            }
        }
        if (!any || search(cluster, xMin, yMin, stamp) < 0) {
            straightTo((area.minX() + area.maxX()) / 2, (area.minY() + area.maxY()) / 2);
        }
    }

    /**
     * Breadth-first from the walk's tile to the nearest cell marked {@code stamp} in {@link #goal},
     * appending the way and ending the walk there: the cell's local index, or -1, with nothing appended,
     * when the cluster holds no way to one.
     */
    private int search(int cluster, int xMin, int yMin, int stamp) {
        int start = local(x, y, xMin, yMin);
        if (start < 0) {
            return -1;
        }
        int[] out = graph.edges(cluster).out;
        int visit = nextGeneration();
        int head = 0;
        int tail = 0;
        seen[start] = visit;
        parent[start] = -1;
        queue[tail++] = start;
        int found = -1;
        while (head < tail) {
            int cell = queue[head++];
            if (goal[cell] == stamp) {
                found = cell;
                break;
            }
            int feasible = out[cell] & ClusterEdges.FEASIBLE_MASK;
            while (feasible != 0) {
                int d = Integer.numberOfTrailingZeros(feasible);
                feasible &= feasible - 1;
                int next = cell + ClusterEdges.STEP[d];
                if (seen[next] != visit) {
                    seen[next] = visit;
                    parent[next] = cell;
                    queue[tail++] = next;
                }
            }
        }
        if (found < 0) {
            return -1;
        }
        int n = 0;
        for (int c = found; c != start; c = parent[c]) {
            queue[n++] = c;
        }
        for (int i = n - 1; i >= 0; i--) {
            add(xMin + queue[i] % CS, yMin + queue[i] / CS, false);
        }
        x = xMin + found % CS;
        y = yMin + found / CS;
        return found;
    }

    private void straightTo(int tx, int ty) {
        x = tx;
        y = ty;
        add(tx, ty, true);
    }

    private void add(int ax, int ay, boolean isJump) {
        if (size == xs.length) {
            int grown = size * 2;
            xs = Arrays.copyOf(xs, grown);
            ys = Arrays.copyOf(ys, grown);
            jump = Arrays.copyOf(jump, grown);
        }
        xs[size] = ax;
        ys[size] = ay;
        jump[size] = isJump;
        size++;
    }

    /** One counter stamps both {@link #goal} and {@link #seen}, so neither can read the other's marks as its own. */
    private int nextGeneration() {
        return ++generation;
    }

    private static int local(int tx, int ty, int xMin, int yMin) {
        int lx = tx - xMin;
        int ly = ty - yMin;
        return lx < 0 || ly < 0 || lx >= CS || ly >= CS ? -1 : ly * CS + lx;
    }
}
