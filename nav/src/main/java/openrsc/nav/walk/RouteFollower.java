package openrsc.nav.walk;

import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Route;

/**
 * Turns a {@link Route} into this tick's aim: which of its steps to head for from where the walker
 * stands, and a walk that reaches it.
 *
 * <p>A seam crossing ({@link Route.Cross}) is a line to cross, not a place to stand on, so the aim is
 * the furthest seam ahead that the {@link WindowSearch} window reaches, and the walk cuts past the
 * nearer ones. A hop or a bank stop bounds that look-ahead: it is aimed at, never past.
 *
 * <p>Not thread-safe: it holds one {@link WindowSearch}.
 */
public final class RouteFollower {

    /** What this tick's walk should do. */
    public sealed interface Aim permits Walk, Delegate {}

    /**
     * Walk toward {@code target}, the target of route step {@code step}. {@code corridor}, when set,
     * is the bounding box of the route's targets up to it, which the search window must cover;
     * {@code onward}, when set, is the target after a seam, which prices where it is crossed.
     * {@code path} is the walk that proved the target reachable, starting on the walker's tile, or
     * null when the aim was not searched.
     */
    public record Walk(
            int step,
            Rect target,
            @Nullable Rect corridor,
            @Nullable Rect onward,
            @Nullable List<Point> path) implements Aim {}

    /** The route's first step is a hop or a bank stop the view drives from here ({@link StepView#drives}). */
    public record Delegate(Route.Step step) implements Aim {}

    private final WindowSearch search;

    public RouteFollower(CollisionMap map) {
        this.search = new WindowSearch(map);
    }

    /** The search aims are proven with; a host's own searches through it share its cached heuristic. */
    public WindowSearch search() {
        return search;
    }

    /**
     * The aim for a host still walking a path it searched earlier toward {@code committed}. While that
     * is still the target of the route's first place or of a seam before it, the aim names it without
     * a search and {@link Walk#path()} is null. A first hop the view reports open still takes precedence.
     */
    public Aim aim(Route route, Point pos, StepView view, WindowSearch.Momentum momentum, @Nullable Rect committed) {
        List<Route.Step> steps = route.steps();
        Route.Step step = steps.get(0);
        if (committed != null && !(step instanceof Route.Hop h && steps.size() > 1 && view.crossingOpen(h))) {
            for (int i = 0; i < steps.size(); i++) {
                Route.Step s = steps.get(i);
                if (committed.equals(stepTarget(s))) {
                    if (s instanceof Route.Hop || s instanceof Route.BankStop) {
                        return i == 0 ? place(s, pos, view) : new Walk(i, committed, corridor(route, i), null, null);
                    }
                    if (s instanceof Route.Arrive && i == 0) {
                        // No corridor, as for an uncommitted arrival: a one-step corridor is the target
                        // itself, and any corridor replaces the window's local square.
                        return new Walk(0, committed, null, null, null);
                    }
                    return new Walk(i, committed, corridor(route, i), onwardOf(route, i), null);
                }
                if (!(s instanceof Route.Cross)) {
                    break;
                }
            }
        }
        // The route was planned on the static map; a crossing the view shows open is walked past.
        if (step instanceof Route.Hop h && steps.size() > 1 && view.crossingOpen(h)) {
            Walk open = reach(route, 1, pos, view, momentum);
            return open != null ? open : new Walk(0, Rect.of(h.landing(), h.landing()), null, null, null);
        }
        if (step instanceof Route.Hop || step instanceof Route.BankStop) {
            return place(step, pos, view);
        }
        if (step instanceof Route.Cross cross) {
            int furthest = 1;
            while (furthest < steps.size() && steps.get(furthest) instanceof Route.Cross) {
                furthest++;
            }
            for (int i = Math.min(furthest, steps.size() - 1); i >= 1; i--) {
                Walk open = reach(route, i, pos, view, momentum);
                if (open != null) {
                    return open;
                }
            }
            return new Walk(0, cross.span(), null, onwardOf(route, 0), null);
        }
        Area target = ((Route.Arrive) step).target();
        return new Walk(0, new Rect(target.minX(), target.minY(), target.maxX(), target.maxY()), null, null, null);
    }

    private static Aim place(Route.Step place, Point pos, StepView view) {
        if (view.drives(place, pos)) {
            return new Delegate(place);
        }
        return new Walk(0, stepTarget(place), null, null, null);
    }

    private @Nullable Walk reach(Route route, int i, Point pos, StepView view, WindowSearch.Momentum momentum) {
        Rect target = stepTarget(route.steps().get(i));
        if (target == null) {
            return null;
        }
        Rect corridor = corridor(route, i);
        Rect onward = onwardOf(route, i);
        List<Point> path = search.run(view, pos, target, corridor, onward, true, momentum);
        return path != null && path.size() >= 2 ? new Walk(i, target, corridor, onward, path) : null;
    }

    /** The tiles a route step heads for: an arrival's area, a seam's span, a hop's entry or a bank stop's tile. */
    public static @Nullable Rect stepTarget(Route.Step step) {
        if (step instanceof Route.Arrive a) {
            return new Rect(
                    a.target().minX(),
                    a.target().minY(),
                    a.target().maxX(),
                    a.target().maxY());
        }
        if (step instanceof Route.Cross c) {
            return c.span();
        }
        if (step instanceof Route.Hop h) {
            return Rect.of(h.entry(), h.entry());
        }
        if (step instanceof Route.BankStop b) {
            return Rect.of(b.bank(), b.bank());
        }
        return null;
    }

    private static @Nullable Rect onwardOf(Route route, int i) {
        List<Route.Step> steps = route.steps();
        if (!(steps.get(i) instanceof Route.Cross) || i + 1 >= steps.size()) {
            return null;
        }
        return stepTarget(steps.get(i + 1));
    }

    private static @Nullable Rect corridor(Route route, int through) {
        Rect box = null;
        for (int i = 0; i <= through && i < route.steps().size(); i++) {
            Rect r = stepTarget(route.steps().get(i));
            if (r == null) {
                continue;
            }
            box = box == null
                    ? r
                    : new Rect(
                            Math.min(box.minX(), r.minX()),
                            Math.min(box.minY(), r.minY()),
                            Math.max(box.maxX(), r.maxX()),
                            Math.max(box.maxY(), r.maxY()));
        }
        return box;
    }
}
