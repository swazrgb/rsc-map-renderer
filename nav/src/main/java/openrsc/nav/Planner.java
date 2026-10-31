package openrsc.nav;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.geometry.Area;
import openrsc.gamedata.geometry.Dest;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.geometry.Targetable;
import openrsc.gamedata.world.ChunkBox;
import openrsc.nav.eligibility.Prereq;

/**
 * Plans trips over one world for any account: full routes ({@link #plan}), travel times
 * ({@link #estimate}, {@link #estimateBatch}, {@link #nearest}) and plain walks ({@link #walk}).
 * {@link Navigation#load} builds one.
 *
 * <p>Answers depend only on the arguments and the world the planner was built over, so equal
 * questions get equal answers and either outcome may be cached. Inside one destination a choice
 * (the route, a bank stop or not, an option of a {@link Dest.AnyOf}) is made by weight: travel time
 * plus the danger charge the policy asks for. What is reported is that choice's travel time, in
 * deci-ticks (10 to a tick).
 *
 * <p>Thread-safe: any number of threads may query one planner at once.
 */
public interface Planner {

    /**
     * The route for {@code request}, or {@link PlanResult.NoRoute}.
     *
     * @throws IllegalArgumentException when the policy's cost overrides name no transport or bank,
     *     or price a bank stop out of range
     */
    PlanResult plan(PlanRequest request);

    /** The gate of the transport a {@link Route.Hop} names; null for a name this planner did not emit. */
    @Nullable
    Prereq gateOf(String transport);

    /**
     * Every transport name with exactly {@code transport}'s gate, {@code transport} among them: the
     * hops one change to the account opens together. The first is the same for every name in the
     * list, so two names of one gate give equal lists.
     */
    default List<String> sameGate(String transport) {
        return List.of(transport);
    }

    /**
     * The walk from {@code from} into {@code to} on foot alone, with no danger charged; null when no
     * walk gets there. The same for every account, since nothing on foot is gated.
     */
    @Nullable
    Leg walk(Point from, Area to);

    /**
     * {@code route}, planned from {@code from}, laid out over the map for drawing: between its steps,
     * the plain shortest walk over the static map. Null from a planner that cannot lay one out.
     */
    @Nullable
    default TracedRoute trace(Point from, Route route) {
        return null;
    }

    /**
     * {@link #walk(Point, Area)} with every tile charged {@link #dangerCharge} for {@code account}:
     * the seams the cheapest such walk crosses. The duration reported is the plain walk's. Equal to
     * the plain walk when the policy charges the account no danger.
     */
    @Nullable
    Leg walk(Point from, Area to, AccountFacts account, NavPolicy policy);

    /**
     * What {@code request}'s answer depends on besides its start tile: two requests with equal keys
     * plan the same trip from wherever they start. Null when the request has no such key, as when its
     * pack or bank is not frozen or its destination can move.
     */
    @Nullable
    Object tripKey(PlanRequest request);

    /**
     * A walk: the seams between the planner's map clusters it crosses, in order, and how long it
     * takes in deci-ticks. The seams are empty for a walk a local search from the start finds.
     */
    record Leg(List<Rect> crossings, int deci) {
        public Leg {
            crossings = List.copyOf(crossings);
        }
    }

    /**
     * How long the route {@link #plan} would take to {@code dest} without a loadout;
     * {@link TravelTime#NO_ROUTE} when nothing reaches it. Always equal to the {@link #estimateBatch}
     * entry for the same destination.
     */
    TravelTime estimate(AccountFacts account, Point from, Dest dest, NavPolicy policy);

    /**
     * {@link #estimate} of each of {@code dests}, in their order, sharing one search from the start.
     * Choosing between the destinations is the caller's, by these times.
     */
    TravelTimes estimateBatch(AccountFacts account, Point from, List<? extends Dest> dests, NavPolicy policy);

    /**
     * {@link #estimate} of the {@link Dest.AnyOf} of every candidate, and which one won: the goals in
     * {@code live}, and the landscape objects {@code statics} names. Null when none is reachable.
     * The landscape objects are searched together rather than priced one by one.
     *
     * <p>The winner is chosen by weight, like any option of one destination. For a choice between
     * separate destinations by time, use {@link #estimateBatch}.
     */
    @Nullable
    Nearest nearest(
            AccountFacts account, Point from, List<? extends Dest> live, StaticTargets statics, NavPolicy policy);

    /**
     * The landscape objects with one of {@code ids}, scenery or boundaries per {@code kind}, as
     * candidates for {@link #nearest}: all but those on a chunk {@code except} holds live and those
     * listed in {@code exceptKeys} ({@link #sceneryKey}, {@link #boundaryKey}), which the caller's own
     * view answers for.
     */
    record StaticTargets(Targetable.Kind kind, int[] ids, ChunkBox except, long[] exceptKeys) {

        /** @throws IllegalArgumentException for a {@code kind} other than scenery or boundary */
        public StaticTargets {
            if (kind != Targetable.Kind.SCENERY && kind != Targetable.Kind.BOUNDARY) {
                throw new IllegalArgumentException("the landscape holds scenery and boundaries, not " + kind);
            }
            ids = ids.clone();
            exceptKeys = exceptKeys.clone();
            Arrays.sort(exceptKeys);
        }

        public static StaticTargets none(Targetable.Kind kind) {
            return new StaticTargets(kind, new int[0], ChunkBox.NONE, new long[0]);
        }

        /** A scenery object's key in {@code exceptKeys}: its anchor tile. */
        public static long sceneryKey(int x, int y) {
            return ((long) x << 32) | (y & 0xFFFFFFFFL);
        }

        /** A boundary's key in {@code exceptKeys}: its tile and edge. */
        public static long boundaryKey(int x, int y, int dir) {
            return ((long) x << 32) | ((long) (y & 0xFFFFFF) << 8) | (dir & 0xFF);
        }

        /** Whether the object of this kind at {@code (x, y)}, on edge {@code dir}, is left to the caller. */
        public boolean excepts(int x, int y, int dir) {
            if (except.isLive(x, y)) {
                return true;
            }
            long key = kind == Targetable.Kind.SCENERY ? sceneryKey(x, y) : boundaryKey(x, y, dir);
            return exceptKeys.length > 0 && Arrays.binarySearch(exceptKeys, key) >= 0;
        }
    }

    /**
     * What {@link #nearest} chose: {@code live}, its index into the live goals, or -1 and the
     * landscape object {@code (id, x, y, dir)}; and the {@link #estimate} to it.
     */
    record Nearest(int live, int id, int x, int y, int dir, TravelTime time) {}

    /** A one-line summary of what the planner's caches hold, for logs. The format is not fixed. */
    String cacheStats();

    /**
     * The danger at {@code (x, y)} for {@code account}, from 0 (none) to {@link #dangerCeiling()}:
     * the unscaled value {@link #dangerCharge} is a multiple of.
     */
    int dangerAt(AccountFacts account, NavPolicy policy, int x, int y);

    /**
     * What every step this planner prices for {@code account} under {@code policy} is charged on top
     * of its time, in deci-ticks: {@link DangerCharge#NONE} when nothing is dangerous for it. A local
     * search that adds it to its own step cost walks where the plan priced.
     */
    DangerCharge dangerCharge(AccountFacts account, NavPolicy policy);

    /** The charge, in deci-ticks, for stepping onto tile {@code (x, y)}. */
    @FunctionalInterface
    interface DangerCharge {
        DangerCharge NONE = (x, y) -> 0;

        int at(int x, int y);
    }

    /**
     * Running totals of the planner's expensive work, {@code {metricBuilds, legPlaneBuilds,
     * legSweeps, searches}}; the difference across one call is what that call did.
     */
    long[] workCounters();

    /**
     * Builds ahead what each distinct routing class in {@code fleet} would build on its first plan,
     * and the walking data around where each account stands.
     *
     * @return how many routing classes there were
     */
    int warmClasses(Collection<? extends AccountFacts> fleet, NavPolicy policy);

    /** The highest value {@link #dangerAt} returns, on any floor and for any account. */
    int dangerCeiling();

    /**
     * How many of {@code fleet} fall in each routing class the planner keeps a metric for, largest
     * first. A class is one combination of usable transports, danger and transport costs.
     */
    int[] classCensus(Collection<? extends AccountFacts> fleet, NavPolicy policy);
}
