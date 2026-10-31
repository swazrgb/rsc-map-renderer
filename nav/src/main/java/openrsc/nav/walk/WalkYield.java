package openrsc.nav.walk;

import java.util.List;
import openrsc.gamedata.geometry.Point;

/**
 * The yield's decision as pure functions: no state and no world, so it can be driven tile by tile.
 *
 * <p>Hostile tiles are packed {@code (x << 32) | z}, {@code count} of them. {@code closing[i]} marks
 * one that stepped nearer the walker last tick, which counts a tile nearer since it will have
 * stepped again by the time the walker's step lands; {@code threat[i]} is its {@link #threat}.
 * {@code reach[i]} is how many tiles past {@code aggroRange} its own aggro radius reaches, which
 * counts it that many tiles nearer. A null {@code closing}, {@code threat} or {@code reach}, which
 * the shorter overloads pass, reads every hostile at its plain Chebyshev distance.
 */
public final class WalkYield {

    private WalkYield() {}

    /** Whether one step between adjacent tiles is legal, bodies included. */
    @FunctionalInterface
    public interface StepCheck {
        boolean canStep(int fromX, int fromY, int toX, int toY);
    }

    /** Half-side of a hostile's threat-mask window, in tiles: one step, then one tile of reach. */
    public static final int THREAT_RADIUS = 2;

    /** A threat mask covering its whole window: the hostile threatens every tile, walls ignored. */
    public static final int THREAT_ALL = (1 << ((2 * THREAT_RADIUS + 1) * (2 * THREAT_RADIUS + 1))) - 1;

    /** The bit for offset {@code (dx, dz)} from a hostile in its threat mask, row-major; 0 outside the window. */
    public static int threatBit(int dx, int dz) {
        if (Math.abs(dx) > THREAT_RADIUS || Math.abs(dz) > THREAT_RADIUS) {
            return 0;
        }
        return 1 << ((dz + THREAT_RADIUS) * (2 * THREAT_RADIUS + 1) + (dx + THREAT_RADIUS));
    }

    /**
     * Whether an NPC on the first tile can attack the second, walls honoured.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L222">NpcBehavior.handleAggro</a>
     */
    @FunctionalInterface
    public interface EngageCheck {
        boolean canEngage(int fromX, int fromY, int toX, int toY);
    }

    /**
     * Whether an NPC on the first tile could walk onto the second by some route, bodies ignored. It
     * may answer true for tiles it cannot walk between.
     */
    @FunctionalInterface
    public interface ConnectCheck {
        boolean connected(int fromX, int fromY, int toX, int toY);
    }

    /** Whether one hostile can attack a tile; built by {@link #threat}. */
    @FunctionalInterface
    public interface Threat {
        boolean threatens(int x, int z);
    }

    /**
     * The tiles an NPC at {@code (nx, nz)} can attack: within {@link #THREAT_RADIUS}, its
     * {@link #threatMask}; further out, a tile it can walk next to and attack across that edge. An NPC
     * sealed off by walls threatens nothing past its mask however wide its aggro radius.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L222">NpcBehavior.handleAggro</a>
     */
    public static Threat threat(int nx, int nz, StepCheck step, EngageCheck engage, ConnectCheck connect) {
        int mask = threatMask(nx, nz, step, engage);
        return (x, z) -> {
            int dx = x - nx, dz = z - nz;
            if (Math.abs(dx) <= THREAT_RADIUS && Math.abs(dz) <= THREAT_RADIUS) {
                return (mask & threatBit(dx, dz)) != 0;
            }
            for (int sz = z - 1; sz <= z + 1; sz++) {
                for (int sx = x - 1; sx <= x + 1; sx++) {
                    if (connect.connected(nx, nz, sx, sz) && engage.canEngage(sx, sz, x, z)) {
                        return true;
                    }
                }
            }
            return false;
        };
    }

    /**
     * The tiles round an NPC at {@code (nx, nz)} it can attack within a tick, as a {@link #threatBit}
     * mask: those it can engage from where it stands or from one legal step away. A fence between them
     * ends its reach even one tile away.
     */
    public static int threatMask(int nx, int nz, StepCheck step, EngageCheck engage) {
        int[] standX = new int[9];
        int[] standZ = new int[9];
        int stands = 0;
        standX[stands] = nx;
        standZ[stands++] = nz;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx == 0 && dz == 0) || !step.canStep(nx, nz, nx + dx, nz + dz)) {
                    continue;
                }
                standX[stands] = nx + dx;
                standZ[stands++] = nz + dz;
            }
        }
        int mask = 0;
        for (int dz = -THREAT_RADIUS; dz <= THREAT_RADIUS; dz++) {
            for (int dx = -THREAT_RADIUS; dx <= THREAT_RADIUS; dx++) {
                int tx = nx + dx, tz = nz + dz;
                for (int i = 0; i < stands; i++) {
                    int sx = standX[i], sz = standZ[i];
                    if (Math.abs(sx - tx) > 1 || Math.abs(sz - tz) > 1) {
                        continue;
                    }
                    if (engage.canEngage(sx, sz, tx, tz)) {
                        mask |= threatBit(dx, dz);
                        break;
                    }
                }
            }
        }
        return mask;
    }

    public enum Action {
        /** Send the walk. */
        ADVANCE,
        /** Stand where the walker is. */
        HOLD,
        /** Step to the decision's tile. */
        BACK_OFF
    }

    /** An action, with the tile to step to for {@link Action#BACK_OFF}; otherwise the walker's own. */
    public static final class Decision {
        public final Action action;
        public final int tileX;
        public final int tileY;

        Decision(Action action, int tileX, int tileY) {
            this.action = action;
            this.tileX = tileX;
            this.tileY = tileY;
        }
    }

    /**
     * Clearance from {@code (x, z)} to the nearest hostile that can reach it: Chebyshev distance, one
     * less for a closing hostile and its {@code reach} less again, skipping a hostile whose
     * {@link #threat} leaves the tile out. {@link Integer#MAX_VALUE} when none counts.
     */
    public static int effClearance(
            long[] hostiles, boolean[] closing, Threat[] threat, int[] reach, int count, int x, int z) {
        int best = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            long p = hostiles[i];
            int hx = (int) (p >> 32), hz = (int) p;
            int dx = x - hx, dz = z - hz;
            int d = Math.max(Math.abs(dx), Math.abs(dz));
            if (threat != null && !threat[i].threatens(x, z)) {
                continue;
            }
            if (closing != null && closing[i]) {
                d -= 1;
            }
            if (reach != null) {
                d -= reach[i];
            }
            if (d < best) {
                best = d;
            }
        }
        return best;
    }

    /**
     * Whether any of steps 1 to {@code lookahead} of {@code forward} (index 0 the walker's tile) has an
     * {@link #effClearance} of {@code aggroRange} or less.
     */
    public static boolean forwardWouldGraze(
            List<int[]> forward,
            int lookahead,
            int aggroRange,
            long[] hostiles,
            boolean[] closing,
            Threat[] threat,
            int[] reach,
            int count) {
        int last = Math.min(lookahead, forward.size() - 1);
        for (int i = 1; i <= last; i++) {
            int[] s = forward.get(i);
            if (effClearance(hostiles, closing, threat, reach, count, s[0], s[1]) <= aggroRange) {
                return true;
            }
        }
        return false;
    }

    /**
     * The best of the walker's tile and its legal neighbours to wait on, as {@code {x, y}}: the most
     * clearance past {@code aggroRange}, counted up to {@code berthRadius + 1}, ties going to the tile
     * nearest {@code target}. Null when none clears {@code aggroRange}.
     */
    public static int[] bestYieldTile(
            int curX,
            int curY,
            int[] target,
            int berthRadius,
            int aggroRange,
            long[] hostiles,
            boolean[] closing,
            Threat[] threat,
            int[] reach,
            int count,
            StepCheck step) {
        int[] best = null;
        int bestScore = aggroRange;
        int bestRegress = Integer.MAX_VALUE;
        int cap = berthRadius + 1;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = curX + dx, cz = curY + dz;
                boolean current = dx == 0 && dz == 0;
                if (!current && !step.canStep(curX, curY, cx, cz)) {
                    continue;
                }
                int clearance = effClearance(hostiles, closing, threat, reach, count, cx, cz);
                if (clearance <= aggroRange) {
                    continue;
                }
                int score = Math.min(clearance, cap);
                int regress = Point.chebyshev(cx, cz, target[0], target[1]);
                if (score > bestScore || (score == bestScore && regress < bestRegress)) {
                    best = new int[] {cx, cz};
                    bestScore = score;
                    bestRegress = regress;
                }
            }
        }
        return best;
    }

    /**
     * This tick's yield. {@code committed} (the wait ran out) advances, as does a walk with no graze
     * within {@code lookahead + 1} steps. Inside that zone it advances while the current and next tile
     * both keep {@code berthRadius + 1}, or while the next step clears {@code aggroRange} and widens
     * clearance; else it backs off to a strictly clearer neighbour, holds on a tile clear of
     * {@code aggroRange}, and boxed in, advances.
     */
    public static Decision decide(
            List<int[]> forward,
            int lookahead,
            int aggroRange,
            long[] hostiles,
            boolean[] closing,
            Threat[] threat,
            int[] reach,
            int count,
            int curX,
            int curY,
            int[] target,
            int berthRadius,
            StepCheck step,
            boolean committed) {
        if (committed) {
            return new Decision(Action.ADVANCE, curX, curY);
        }
        if (!forwardWouldGraze(forward, lookahead + 1, aggroRange, hostiles, closing, threat, reach, count)) {
            return new Decision(Action.ADVANCE, curX, curY);
        }
        int cap = berthRadius + 1;
        int curClear = effClearance(hostiles, closing, threat, reach, count, curX, curY);
        if (curClear >= cap
                && forward.size() > 1
                && effClearance(hostiles, closing, threat, reach, count, forward.get(1)[0], forward.get(1)[1]) >= cap) {
            return new Decision(Action.ADVANCE, curX, curY);
        }
        if (forward.size() > 1) {
            int nextClear = effClearance(hostiles, closing, threat, reach, count, forward.get(1)[0], forward.get(1)[1]);
            if (nextClear > aggroRange && nextClear > curClear) {
                return new Decision(Action.ADVANCE, curX, curY);
            }
        }
        int[] refuge = bestYieldTile(
                curX, curY, target, berthRadius, aggroRange, hostiles, closing, threat, reach, count, step);
        if (refuge != null
                && (refuge[0] != curX || refuge[1] != curY)
                && Math.min(effClearance(hostiles, closing, threat, reach, count, refuge[0], refuge[1]), cap)
                        > Math.min(curClear, cap)) {
            return new Decision(Action.BACK_OFF, refuge[0], refuge[1]);
        }
        if (curClear > aggroRange) {
            return new Decision(Action.HOLD, curX, curY);
        }
        return new Decision(Action.ADVANCE, curX, curY);
    }
}
