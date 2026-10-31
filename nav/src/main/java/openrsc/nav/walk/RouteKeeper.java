package openrsc.nav.walk;

import java.util.ArrayList;
import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.PlanRequest;
import openrsc.nav.PlanResult;
import openrsc.nav.Planner;
import openrsc.nav.Route;

/**
 * Keeps the route of the trip being walked, and re-guides the walk to its current place every tick.
 *
 * <p>The route is planned once and kept while the walk follows it: the hops and bank stops it chose
 * stand, and only the walk to the next of them is asked again ({@link Planner#walk}), from the
 * walker's tile: along the danger field where the account is charged danger, else the plain walk. Its seams are
 * re-derived every tick and need no tracking; a place is done in one way only:
 *
 * <ul>
 *   <li>a hop, by standing on its {@link Route.Hop#landing()};
 *   <li>a bank stop or the arrival, by standing on it, which plans the trip again from there.
 * </ul>
 *
 * <p>Anything else that moves the walker is not a finished place. The trip is planned again when its
 * {@link Planner#tripKey} changes, when the walker moved further between calls than a tick's walk,
 * when the walk to the place grows by more than walking could explain, when it stops getting
 * shorter, or after {@link #invalidate()}.
 *
 * <p>One instance per walker, asked once a tick; not thread-safe.
 */
public final class RouteKeeper {

    /** Tiles the walker may move between calls and still be on the kept walk: one a tick, one spare. */
    private static final int STRIDE = 2;

    /** Deci-ticks the walk to the current place may grow in one call and still be a walk. */
    private static final int JUMP_DECI = 200;

    /** Calls the walk to the current place may go without getting shorter before the trip is replanned. */
    private static final int STALL_TICKS = 50;

    private final Planner nav;
    private @Nullable Object keptKey;
    private @Nullable Route kept;
    private @Nullable Point keptFrom;
    private int head;
    private int rest;

    private @Nullable Point lastAsked;
    private int bestLead = Integer.MAX_VALUE;

    private int sinceImprove;
    private int plans;
    private int leads;
    private int walks;
    private boolean fieldLead;
    private @Nullable Planner.Leg heldLead;

    private @Nullable Point heldFrom;

    public RouteKeeper(Planner nav) {
        this.nav = nav;
    }

    /** Full plans of the trip asked of the planner so far. */
    public int plans() {
        return plans;
    }

    /** Walks along the danger field asked of the planner so far. */
    public int leads() {
        return leads;
    }

    /** Plain walks asked of the planner so far. */
    public int walks() {
        return walks;
    }

    /** The kept route as the planner gave it, before any re-guiding; null when none is kept. */
    public @Nullable Route kept() {
        return kept;
    }

    /** The tile {@link #kept()} was planned from. */
    public @Nullable Point keptFrom() {
        return keptFrom;
    }

    /** Whether a route is kept for {@code request}'s trip, which {@link #plan} would re-guide. */
    public boolean keeps(PlanRequest request) {
        return kept != null && keptKey != null && keptKey.equals(nav.tripKey(request));
    }

    /** Drops the kept route: the next {@link #plan} plans the trip afresh. */
    public void invalidate() {
        kept = null;
        keptFrom = null;
        keptKey = null;
        fieldLead = false;
        heldLead = null;
        heldFrom = null;
        bestLead = Integer.MAX_VALUE;
        sinceImprove = 0;
    }

    /**
     * Whether the route last returned is guided along the danger field. A search walking it then
     * charges the field beyond what the walker can see ({@link StepView#beyond}), or it cuts through
     * the field the guidance went round.
     */
    public boolean leadFollowsField() {
        return fieldLead;
    }

    /**
     * The route for {@code request}: the kept one, or a fresh plan, with the walk to its current place
     * guided from {@code request}'s tile. A planner answer other than {@link PlanResult.Planned} is
     * returned as it is and drops the kept route. The route's {@link Route#totalCostDeci()} is the walk
     * to the current place plus the kept route's cost beyond it.
     */
    public PlanResult plan(PlanRequest request) {
        Point pos = request.from();
        Object key = nav.tripKey(request);
        Point previous = lastAsked;
        lastAsked = pos;
        Route route = kept;
        boolean keep = route != null
                && key != null
                && key.equals(keptKey)
                && previous != null
                && Point.chebyshev(previous.x(), previous.y(), pos.x(), pos.y()) <= STRIDE;
        boolean crossed = false;
        if (keep) {
            while (route.steps().get(head) instanceof Route.Hop h && h.landing().equals(pos)) {
                int next = place(route, head + 1);
                if (next < 0) {
                    // A route ends in its arrival, so there is always a next place; plan again if not.
                    keep = false;
                    break;
                }
                head = next;
                crossed = true;
                bestLead = Integer.MAX_VALUE;
                sinceImprove = 0;
            }
        }
        if (keep && settles(route.steps().get(head)) && standsOn(route.steps().get(head), pos)) {
            // What happens on a bank stop or the arrival changes the pack: the next plan states the
            // trip from the far side of it.
            keep = false;
        }
        Planner.Leg lead = null;
        boolean steadied = false;
        if (keep) {
            lead = lead(request, route.steps().get(head));
            keep = lead != null && (crossed || onPlan(lead.deci()));
            if (keep && crossed) {
                // The walk from the landing was part of rest; it is the lead now.
                rest -= lead.deci();
                bestLead = lead.deci();
            }
            steadied = keep && !crossed;
        }
        if (!keep) {
            PlanResult r = nav.plan(request);
            plans++;
            if (!(r instanceof PlanResult.Planned(Route planned))) {
                invalidate();
                return r;
            }
            kept = planned;
            keptFrom = pos;
            route = planned;
            keptKey = key;
            head = place(planned, 0);
            fieldLead = false;
            bestLead = Integer.MAX_VALUE;
            sinceImprove = 0;
            Route.Step stop = planned.steps().get(head);
            boolean on = standsOn(stop, pos);
            lead = on ? null : lead(request, stop);
            heldLead = null;
            heldFrom = null;
            if (lead == null) {
                // Standing on the place is a walk of nothing, a baseline that catches a jump next call.
                rest = planned.totalCostDeci();
                bestLead = on ? 0 : Integer.MAX_VALUE;
                return r;
            }
            rest = planned.totalCostDeci() - lead.deci();
            bestLead = lead.deci();
        }
        return new PlanResult.Planned(guided(route, steadied ? steady(lead, pos) : hold(lead, pos)));
    }

    /**
     * {@code fresh}, unless it turns to another corridor without beating the held lead: then the held
     * lead's seams at {@code fresh}'s duration. A lead's cost wobbles by a few tiles from one tile to
     * the next where it reads a seam's span as one place, so neighbouring tiles can each lead the other
     * way, and a walk whose steps land a tick late would trade them. Another corridor wins once it
     * beats the held one less a tick for every tile walked since.
     */
    private Planner.Leg steady(Planner.Leg fresh, Point pos) {
        Planner.Leg held = heldLead;
        Point from = heldFrom;
        if (held != null && from != null && !sameCorridor(held, fresh)) {
            int walked = WindowSearch.STEP_COST * Point.chebyshev(from.x(), from.y(), pos.x(), pos.y());
            if (fresh.deci() >= held.deci() - walked) {
                return new Planner.Leg(held.crossings(), fresh.deci());
            }
        }
        return hold(fresh, pos);
    }

    private Planner.Leg hold(Planner.Leg lead, Point pos) {
        heldLead = lead;
        heldFrom = pos;
        return lead;
    }

    private static boolean sameCorridor(Planner.Leg held, Planner.Leg fresh) {
        return fresh.crossings().isEmpty()
                || held.crossings().contains(fresh.crossings().getFirst());
    }

    private boolean onPlan(int leadDeci) {
        if (bestLead == Integer.MAX_VALUE) {
            bestLead = leadDeci;
            sinceImprove = 0;
            return true;
        }
        if (leadDeci > bestLead + JUMP_DECI) {
            return false;
        }
        if (leadDeci < bestLead) {
            bestLead = leadDeci;
            sinceImprove = 0;
            return true;
        }
        // A walk of nothing is a place being driven, which takes as many ticks as the server gives it.
        return leadDeci == 0 || ++sinceImprove <= STALL_TICKS;
    }

    /** The walk from {@code request}'s tile to {@code stop}; null when nothing on foot reaches it. */
    private @Nullable Planner.Leg lead(PlanRequest request, Route.Step stop) {
        Area area = stopArea(stop);
        Point pos = request.from();
        fieldLead = false;
        if (!request.policy().chargesDanger(request.account().stats())) {
            walks++;
            return nav.walk(pos, area);
        }
        leads++;
        Planner.Leg lead = nav.walk(pos, area, request.account(), request.policy());
        fieldLead = lead != null;
        return lead;
    }

    private Route guided(Route route, Planner.Leg lead) {
        List<Route.Step> steps = route.steps();
        List<Route.Step> out = new ArrayList<>(lead.crossings().size() + steps.size() - head);
        for (Rect span : lead.crossings()) {
            out.add(new Route.Cross(span, steps.get(head).pack()));
        }
        out.addAll(steps.subList(head, steps.size()));
        return new Route(out, rest + lead.deci(), route.debits());
    }

    private static int place(Route route, int from) {
        List<Route.Step> steps = route.steps();
        for (int i = from; i < steps.size(); i++) {
            if (!(steps.get(i) instanceof Route.Cross)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether standing on {@code stop} finishes it: a hop is finished by its landing instead. */
    private static boolean settles(Route.Step stop) {
        return !(stop instanceof Route.Hop);
    }

    private static Area stopArea(Route.Step stop) {
        return switch (stop) {
            case Route.Hop h -> h.entry();
            case Route.BankStop b -> b.bank();
            case Route.Arrive a -> a.target();
            case Route.Cross c -> throw new IllegalArgumentException("a seam is not a stop: " + c);
        };
    }

    private static boolean standsOn(Route.Step stop, @Nullable Point pos) {
        return pos != null && stopArea(stop).contains(pos.x(), pos.y());
    }
}
