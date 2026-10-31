package openrsc.nav.walk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.NavPolicy;

/**
 * A walker's yield: each tick, whether the walk it is about to send would come within aggro range of
 * an NPC, and if so whether to advance anyway, hold or back off ({@link WalkYield#decide}). It keeps
 * what that needs across ticks: last tick's NPC tiles, to tell which are closing, and the wait
 * budget, which commits the walk through a graze after {@link #PATIENCE} ticks without new ground.
 *
 * <p>Only NPCs count, and only those {@link NavPolicy#berth()} berths and {@link Berth#aggressiveTo}
 * says attack this account. Call {@link #roll} then {@link #decide} once a tick; not thread-safe.
 */
public final class Yield {

    /** An NPC as the yield reads it. */
    public interface Sighting extends Berth.Sighting {
        /** The NPC's definition id. */
        int id();

        /** The NPC's server index, its identity from one tick to the next. */
        int serverIndex();

        boolean inCombat();
    }

    /**
     * The server's default aggro range, in tiles (Chebyshev): {@code aggro_range}, which both worlds
     * leave at 1. A step grazes an aggressor that comes this near, or as near as its own
     * {@link Berth#aggroRadius} when that is wider; clearances count from that edge.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L467">ServerConfiguration.AGGRO_RANGE</a>
     */
    public static final int AGGRO_RANGE = 1;
    /** Clearance the yield tries to keep, in tiles; a refuge's clearance counts up to one past it. */
    public static final int BERTH_RADIUS = 2;
    /** Steps down the walk a graze is looked for; {@link WalkYield#decide} looks one further. */
    public static final int LOOKAHEAD = BERTH_RADIUS;
    /** Ticks penned beside an aggressor without reaching a new tile before the walk pushes through. */
    public static final int PATIENCE = 8;
    // The deepest tile scored is LOOKAHEAD + 1 out, an NPC bears on it from one further, and a closing
    // one counts one nearer still.
    private static final int SCAN_RADIUS = LOOKAHEAD + 3;

    /**
     * Ticks after the player's own retreat in which no NPC aggroes it: the retreat resets its combat
     * timer and leaves it running, and a running player waits five ticks. After a fight that ends any
     * other way the wait is {@code ceil(640 / game_tick) - 1} ticks, 0 on Uranium and 1 on Coleslaw,
     * over before a walk sent once the fight is seen to end is judged.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/WalkRequest.java#L59">WalkRequest.process</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L396">NpcBehavior.canAggro</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/event/rsc/impl/combat/CombatEvent.java#L102">CombatEvent.onDeath</a>
     */
    private static final int RAN_AWAY_TICKS = 5;

    private final Map<Integer, Long> prevTile = new HashMap<>();

    private final Map<Integer, Long> curTile = new HashMap<>();

    private int stallTicks;
    private boolean committed;
    private final Set<Long> visitedWhilePenned = new HashSet<>();

    private String branch = "idle";
    private int hostiles;
    private int nearest = -1;
    private boolean wouldGraze;
    private @Nullable List<int[]> forward;
    private int backOffX = Integer.MIN_VALUE;
    private int backOffZ = Integer.MIN_VALUE;

    /**
     * Connects tiles that share a walking component ({@link CollisionMap#componentId}) of the map as
     * built, which reads the doors anyone may open as open and misses one a plugin holds open for a
     * moment. An NPC on a tile with no component is connected to everything.
     */
    public static WalkYield.ConnectCheck byComponent(CollisionMap map) {
        return (fx, fy, tx, ty) -> {
            int from = map.componentId(fx, fy);
            return from == 0 || from == map.componentId(tx, ty);
        };
    }

    /**
     * Rolls the NPC tiles on a tick; call once a tick, before {@link #decide}. {@code ghosts} are NPCs
     * last seen at the view's edge, which count too.
     */
    public void roll(List<? extends Sighting> live, List<? extends Sighting> ghosts) {
        prevTile.clear();
        prevTile.putAll(curTile);
        curTile.clear();
        for (Sighting n : live) {
            curTile.put(n.serverIndex(), pack(n.x(), n.z()));
        }
        for (Sighting n : ghosts) {
            curTile.putIfAbsent(n.serverIndex(), pack(n.x(), n.z()));
        }
    }

    /**
     * What to do with {@code chunk}, the walk about to be sent (index 0 the walker's tile), toward
     * {@code target}. {@code berthExempt} is the server index of the one NPC the walk is aimed at,
     * which is never yielded to; -1 for none. {@code sinceRetreat} is how many ticks ago the server
     * confirmed the walker's own retreat, {@link Integer#MAX_VALUE} for never. {@code step} is one
     * legal step, bodies included, {@code engage} whether an NPC on the first tile can attack the
     * second, and {@code connect} whether it could walk there at all.
     */
    public WalkYield.Decision decide(
            Point pos,
            Rect target,
            List<Point> chunk,
            List<? extends Sighting> live,
            List<? extends Sighting> ghosts,
            int combatLevel,
            boolean inCombat,
            int sinceRetreat,
            NavPolicy pol,
            int berthExempt,
            WalkYield.StepCheck step,
            WalkYield.EngageCheck engage,
            WalkYield.ConnectCheck connect) {
        // A walk sent now moves next tick, and the NPCs, which process before players, judge it the tick after.
        boolean ranAway = sinceRetreat <= RAN_AWAY_TICKS - 2;
        if (inCombat || ranAway) {
            branch = inCombat ? "FIGHTING" : "RAN_AWAY";
            hostiles = 0;
            nearest = -1;
            wouldGraze = false;
            forward = null;
            backOffX = Integer.MIN_VALUE;
            backOffZ = Integer.MIN_VALUE;
            trackBudget(pos, live, combatLevel, inCombat, pol, berthExempt, false);
            return new WalkYield.Decision(WalkYield.Action.ADVANCE, pos.x(), pos.y());
        }
        Threats th = threats(pos, live, ghosts, combatLevel, pol, berthExempt, step, engage, connect);
        List<int[]> fwd = new ArrayList<>(chunk.size());
        for (Point p : chunk) {
            fwd.add(new int[] {p.x(), p.y()});
        }
        boolean graze = th.count > 0
                && WalkYield.forwardWouldGraze(
                        fwd, LOOKAHEAD, AGGRO_RANGE, th.tiles, th.closing, th.threat, th.reach, th.count);
        trackBudget(pos, live, combatLevel, inCombat, pol, berthExempt, graze);
        hostiles = th.count;
        nearest = th.count == 0
                ? -1
                : WalkYield.effClearance(th.tiles, th.closing, th.threat, th.reach, th.count, pos.x(), pos.y());
        wouldGraze = graze;
        forward = fwd;
        backOffX = Integer.MIN_VALUE;
        backOffZ = Integer.MIN_VALUE;
        if (th.count == 0) {
            branch = "NO_HOSTILE";
            return new WalkYield.Decision(WalkYield.Action.ADVANCE, pos.x(), pos.y());
        }
        WalkYield.Decision d = WalkYield.decide(
                fwd,
                LOOKAHEAD,
                AGGRO_RANGE,
                th.tiles,
                th.closing,
                th.threat,
                th.reach,
                th.count,
                pos.x(),
                pos.y(),
                new int[] {target.minX(), target.minY()},
                BERTH_RADIUS,
                step,
                committed);
        branch = d.action.name();
        if (d.action == WalkYield.Action.BACK_OFF) {
            backOffX = d.tileX;
            backOffZ = d.tileY;
        }
        return d;
    }

    /**
     * The branch the last {@link #decide} took, for diagnostics: {@code idle}, {@code FIGHTING},
     * {@code RAN_AWAY}, {@code NO_HOSTILE} or a {@link WalkYield.Action} name.
     */
    public String branch() {
        return branch;
    }

    public int hostiles() {
        return hostiles;
    }

    /** The last decision's clearance to the nearest hostile ({@link WalkYield#effClearance}); -1 for none. */
    public int nearest() {
        return nearest;
    }

    public boolean wouldGraze() {
        return wouldGraze;
    }

    public boolean committed() {
        return committed;
    }

    public int stallTicks() {
        return stallTicks;
    }

    /** The walk the last decision judged, index 0 the walker's tile; null when none was judged. */
    public @Nullable List<int[]> forward() {
        return forward;
    }

    /** The tile the last decision backed off to; {@link Integer#MIN_VALUE} when it did not. */
    public int backOffX() {
        return backOffX;
    }

    public int backOffZ() {
        return backOffZ;
    }

    private static final class Threats {
        long[] tiles = new long[0];
        boolean[] closing = new boolean[0];
        WalkYield.Threat[] threat = new WalkYield.Threat[0];
        int[] reach = new int[0];
        int count;
    }

    private Threats threats(
            Point pos,
            List<? extends Sighting> live,
            List<? extends Sighting> ghosts,
            int combatLevel,
            NavPolicy pol,
            int berthExempt,
            WalkYield.StepCheck step,
            WalkYield.EngageCheck engage,
            WalkYield.ConnectCheck connect) {
        Threats t = new Threats();
        if (!pol.berth().berthsAnything()) {
            return t;
        }
        boolean meWild = AccountFacts.wildernessLevel(pos.x(), pos.y()) > 0;
        List<Long> tiles = new ArrayList<>();
        List<Boolean> closing = new ArrayList<>();
        List<WalkYield.Threat> threats = new ArrayList<>();
        List<Integer> reaches = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        List<Sighting> all = new ArrayList<>(live.size() + ghosts.size());
        all.addAll(live);
        all.addAll(ghosts);
        for (Sighting n : all) {
            // An NPC already fighting does not aggro.
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L96
            if (n.inCombat()
                    || !Berth.aggressiveTo(n.def(), n.x(), n.z(), combatLevel, meWild)
                    || n.serverIndex() == berthExempt
                    || !pol.berth().berths(n.id(), n.serverIndex())
                    || !seen.add(n.serverIndex())) {
                continue;
            }
            int reach = Berth.aggroRadius(n.def()) - AGGRO_RANGE;
            if (Point.chebyshev(pos.x(), pos.y(), n.x(), n.z()) - reach > SCAN_RADIUS) {
                continue;
            }
            tiles.add(pack(n.x(), n.z()));
            closing.add(closingOn(pos, n.serverIndex(), n.x(), n.z()));
            threats.add(WalkYield.threat(n.x(), n.z(), step, engage, connect));
            reaches.add(reach);
        }
        t.count = tiles.size();
        t.tiles = new long[t.count];
        t.closing = new boolean[t.count];
        t.threat = new WalkYield.Threat[t.count];
        t.reach = new int[t.count];
        for (int i = 0; i < t.count; i++) {
            t.tiles[i] = tiles.get(i);
            t.closing[i] = closing.get(i);
            t.threat[i] = threats.get(i);
            t.reach[i] = reaches.get(i);
        }
        return t;
    }

    private boolean closingOn(Point pos, int serverIndex, int nx, int nz) {
        Long prev = prevTile.get(serverIndex);
        if (prev == null) {
            return false;
        }
        int px = (int) (prev.longValue() >> 32);
        int pz = (int) prev.longValue();
        return Point.chebyshev(pos.x(), pos.y(), px, pz) > Point.chebyshev(pos.x(), pos.y(), nx, nz);
    }

    /**
     * Spends the wait budget on a tick penned without reaching a new tile, and latches the commit once
     * it passes {@link #PATIENCE}. Only new ground refunds it, never a clear read: a fight makes the
     * way read clear, and a refund would hand the walker a fresh wait to back away again. The ground
     * covered is forgotten once nothing pens the walker.
     */
    private void trackBudget(
            Point pos,
            List<? extends Sighting> live,
            int combatLevel,
            boolean inCombat,
            NavPolicy pol,
            int berthExempt,
            boolean graze) {
        if (!pennedNear(pos, live, combatLevel, inCombat, pol, berthExempt)) {
            visitedWhilePenned.clear();
            stallTicks = 0;
            committed = false;
            return;
        }
        if (visitedWhilePenned.add(pack(pos.x(), pos.y()))) {
            stallTicks = 0;
        } else {
            stallTicks++;
        }
        if (stallTicks > PATIENCE) {
            committed = true;
        } else if (!graze) {
            committed = false;
        }
    }

    /**
     * Whether an aggressor is within {@code BERTH_RADIUS + LOOKAHEAD}, nearer by however far its aggro
     * radius reaches past {@link #AGGRO_RANGE}, or the walker is fighting: a fight is not dodged, but
     * it makes no progress, so it runs the budget down.
     */
    private static boolean pennedNear(
            Point pos,
            List<? extends Sighting> live,
            int combatLevel,
            boolean inCombat,
            NavPolicy pol,
            int berthExempt) {
        if (inCombat) {
            return true;
        }
        boolean meWild = AccountFacts.wildernessLevel(pos.x(), pos.y()) > 0;
        for (Sighting n : live) {
            if (!Berth.aggressiveTo(n.def(), n.x(), n.z(), combatLevel, meWild)
                    || n.serverIndex() == berthExempt
                    || !pol.berth().berths(n.id(), n.serverIndex())) {
                continue;
            }
            int reach = Berth.aggroRadius(n.def()) - AGGRO_RANGE;
            if (Point.chebyshev(pos.x(), pos.y(), n.x(), n.z()) - reach <= BERTH_RADIUS + LOOKAHEAD) {
                return true;
            }
        }
        return false;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
