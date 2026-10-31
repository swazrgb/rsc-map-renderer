package openrsc.nav.walk;

import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerRules.NpcBlocking;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.geometry.Point;

/**
 * The berth a walk keeps from the NPCs in view, stamped in a square of {@link #RADIUS} round the
 * walker's tile. Two separate rules:
 *
 * <ul>
 *   <li>A hard block on the tile of an NPC the world's NPC-blocking rule makes a body
 *       ({@link NpcBlocking#blocks}): the server refuses a step onto it.
 *   <li>Rings of cost round every NPC that attacks this account ({@link #aggressiveTo}), whatever
 *       the blocking rule: {@link #BERTH_COST} inside its aggro range ({@link #aggroRadius}),
 *       {@link #NEAR_COST} a tile further out, in range once the NPC steps, and {@link #FAR_COST} a
 *       tile further still. Rings are a price, never a wall.
 * </ul>
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/PathValidation.java#L672">PathValidation.isMobBlocking</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L111">NpcBehavior.handleRoam</a>
 */
public final class Berth {

    /** An NPC as the berth reads it. */
    public interface Sighting {
        int x();

        int z();

        /** The NPC's definition; null when unknown, which casts no rings. */
        NpcDef def();
    }

    /** How far the rings reach round an NPC of the default aggro range, in tiles (Chebyshev). */
    public static final int RING_RADIUS = 3;

    /**
     * How far from a player the server shows it NPCs, in tiles (Chebyshev).
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Mob.java#L369">Mob.withinRange</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L506">ServerConfiguration.VIEW_DISTANCE</a>
     */
    public static final int NPC_VIEW = 15;

    /** Half-side of the stamped square, in tiles: the NPCs in view, plus their rings. */
    public static final int RADIUS = NPC_VIEW + RING_RADIUS;

    /** Deci-tick cost of a tile inside an aggressor's aggro range ({@link #aggroRadius}). */
    public static final int BERTH_COST = 60;

    /** Deci-tick cost of a tile one past an aggressor's aggro range. */
    public static final int NEAR_COST = 30;

    /** Deci-tick cost of a tile two past an aggressor's aggro range. */
    public static final int FAR_COST = 10;

    /** No blocks and no rings. */
    public static final Berth EMPTY = new Berth(0, 0, 0, 0, null, null, null);

    private final int originX;
    private final int originY;
    private final int width;
    private final int height;
    private final long[] blocked;
    private final int[] cost;

    private final long[] near;

    private Berth(int originX, int originY, int width, int height, long[] blocked, int[] cost, long[] near) {
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.blocked = blocked;
        this.cost = cost;
        this.near = near;
    }

    /** Whether a body-blocking NPC stands on {@code (x, y)}; false outside the stamped square. */
    public boolean blocks(int x, int y) {
        return bit(blocked, x, y);
    }

    private boolean bit(long[] bits, int x, int y) {
        if (bits == null) {
            return false;
        }
        int bx = x - originX;
        int by = y - originY;
        if (bx < 0 || by < 0 || bx >= width || by >= height) {
            return false;
        }
        int idx = by * width + bx;
        return (bits[idx >> 6] & (1L << (idx & 63))) != 0;
    }

    /** The summed ring cost of {@code (x, y)}, in deci-ticks; 0 outside the stamped square. */
    public int cost(int x, int y) {
        if (cost == null) {
            return 0;
        }
        int bx = x - originX;
        int by = y - originY;
        if (bx < 0 || by < 0 || bx >= width || by >= height) {
            return 0;
        }
        return cost[by * width + bx];
    }

    /** Whether {@code (x, y)} is within a tile past an aggressor's aggro range: in it once the NPC steps. */
    public boolean inReach(int x, int y) {
        return bit(near, x, y);
    }

    /**
     * The ring cost {@code players} put on {@code (x, y)}, in deci-ticks: the rings an aggressor
     * casts. A player is never a hard block.
     */
    public static int playerCost(List<Point> players, int x, int y) {
        int sum = 0;
        for (Point p : players) {
            int ring = Math.max(Math.abs(x - p.x()), Math.abs(y - p.y()));
            if (ring <= RING_RADIUS) {
                sum += ring <= 1 ? BERTH_COST : ring == 2 ? NEAR_COST : FAR_COST;
            }
        }
        return sum;
    }

    /**
     * Whether an NPC of {@code d} standing on {@code (x, z)} attacks an account of {@code combatLevel}:
     * it is aggressive and attackable, and the account is under twice its level plus one or both of
     * them are in the wilderness ({@code meWild} for the account). A level of 0 or less counts every
     * aggressive NPC.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L381">NpcBehavior.aggressiveCheck</a>
     */
    public static boolean aggressiveTo(NpcDef d, int x, int z, int combatLevel, boolean meWild) {
        return d != null
                && d.aggressive()
                && d.attackable()
                && (combatLevel <= 0
                        || combatLevel < d.combatLevel() * 2 + 1
                        || (meWild && AccountFacts.wildernessLevel(x, z) > 0));
    }

    /**
     * How near a player must stand for an NPC of {@code d} to aggro it, in tiles (Chebyshev): the
     * world's {@code aggro_range}, {@link Yield#AGGRO_RANGE} on both worlds, except for the three NPCs
     * the server gives a radius of their own. A null def reads the default.
     *
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L50">NpcBehavior.NpcBehavior</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/npc/NpcBehavior.java#L111">NpcBehavior.handleRoam</a>
     * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/model/entity/Entity.java#L62">Entity.withinRange</a>
     */
    public static int aggroRadius(@Nullable NpcDef d) {
        if (d == null) {
            return Yield.AGGRO_RANGE;
        }
        return switch (NpcId.getById(d.id())) {
            case BANDIT_AGGRESSIVE -> 2;
            case BLACK_KNIGHT -> 10;
            case UNDEADONE -> 3;
            default -> Yield.AGGRO_RANGE;
        };
    }

    /** How far the rings of an aggressor of {@code d} reach from its tile, in tiles (Chebyshev). */
    public static int ringSpan(@Nullable NpcDef d) {
        return RING_RADIUS + aggroRadius(d) - Yield.AGGRO_RANGE;
    }

    /**
     * Whether the rings of an NPC among {@code sightings} that attacks an account of {@code combatLevel}
     * standing on {@code (x, y)} reach that tile, however far it lies from the walker. The rings are
     * {@link #build}'s, but the account's wilderness is read at {@code (x, y)}, where {@link #build}
     * reads it at the walker's tile.
     */
    public static boolean ringsReach(List<? extends Sighting> sightings, int x, int y, int combatLevel) {
        boolean meWild = AccountFacts.wildernessLevel(x, y) > 0;
        for (Sighting n : sightings) {
            NpcDef d = n.def();
            if (aggressiveTo(d, n.x(), n.z(), combatLevel, meWild)
                    && Math.max(Math.abs(x - n.x()), Math.abs(y - n.z())) <= ringSpan(d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The berth round {@code pos} for an account of {@code combatLevel} (0 or less: every aggressive
     * NPC casts rings) from {@code sightings}, under the world's {@code blocking} rule. The rings are
     * stamped whatever the walker's policy; whether to charge them is the reader's choice.
     */
    public static Berth build(Point pos, List<? extends Sighting> sightings, int combatLevel, NpcBlocking blocking) {
        if (sightings.isEmpty()) {
            return EMPTY;
        }
        int w = 2 * RADIUS + 1;
        int h = w;
        int ox = pos.x() - RADIUS;
        int oy = pos.y() - RADIUS;
        long[] blocked = null;
        int[] cost = null;
        long[] near = null;
        boolean meWild = AccountFacts.wildernessLevel(pos.x(), pos.y()) > 0;
        for (Sighting n : sightings) {
            NpcDef d = n.def();
            int bx = n.x() - ox;
            int by = n.z() - oy;
            if (blocking.blocks(d, combatLevel) && bx >= 0 && by >= 0 && bx < w && by < h) {
                if (blocked == null) {
                    blocked = new long[(w * h + 63) / 64];
                }
                int idx = by * w + bx;
                blocked[idx >> 6] |= 1L << (idx & 63);
            }
            if (!aggressiveTo(d, n.x(), n.z(), combatLevel, meWild)) {
                continue;
            }
            if (cost == null) {
                cost = new int[w * h];
                near = new long[(w * h + 63) / 64];
            }
            int reach = aggroRadius(d) - Yield.AGGRO_RANGE;
            int span = ringSpan(d);
            for (int dy = -span; dy <= span; dy++) {
                for (int dx = -span; dx <= span; dx++) {
                    int tx = n.x() + dx - ox;
                    int ty = n.z() + dy - oy;
                    if (tx >= 0 && ty >= 0 && tx < w && ty < h) {
                        int ring = Math.max(Math.abs(dx), Math.abs(dy)) - reach;
                        int idx = ty * w + tx;
                        cost[idx] += ring <= 1 ? BERTH_COST : ring == 2 ? NEAR_COST : FAR_COST;
                        if (ring <= 2) {
                            near[idx >> 6] |= 1L << (idx & 63);
                        }
                    }
                }
            }
        }
        if (blocked == null && cost == null) {
            return EMPTY;
        }
        return new Berth(ox, oy, w, h, blocked, cost, near);
    }
}
