package openrsc.nav.transports.spec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import openrsc.bot.api.stats.Skill;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.ItemId;
import openrsc.gamedata.defs.BoundaryLocs;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.Overlays;
import openrsc.nav.Transport;
import openrsc.nav.eligibility.EligibilityInputs.Fact;
import openrsc.nav.eligibility.Kit;
import openrsc.nav.eligibility.Prereq;
import openrsc.nav.transports.JungleVineTransport;
import openrsc.nav.transports.WebTransport;

/**
 * Every placement of a boundary that is cut with a carried item and then stepped through: a web or a
 * jungle vine. A wall on a tile edge opens the crossing between its two flanks; a diagonal boundary
 * fills its own tile, and opens each axis whose two neighbours can be stepped to from that tile.
 *
 * <p>A web is cut with a knife or a melee weapon, or, on a world with left-click webs, by clicking it
 * with any weapon or a knife carried; both directions cross. A vine needs Woodcutting 50 and a
 * machete, and the chop moves the player onto the vine's own tile, so a wall-edge vine crosses only
 * towards that tile. A vine is declared only where no walk of 10 steps or fewer reaches the far
 * side.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L30">CutWeb.onUseBound</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L51">CutWeb.blockOpBound</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L65">WoodcutJungle.handleJungleWoodcut</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L127">WoodcutJungle.cutJungle</a>
 */
public record CutBoundary(int boundaryId) implements TransportSpec {

    private static final int WEB_COST = 110;

    static final int JUNGLE_COST = 80;

    static final int WALKAROUND_BUDGET = JUNGLE_COST + 20;

    static final int WOODCUTTING_REQ = 50;

    public static CutBoundary webs() {
        return new CutBoundary(WebTransport.WEB_ID);
    }

    public static CutBoundary vines() {
        return new CutBoundary(JungleVineTransport.VINE_ID);
    }

    @Override
    public boolean namedByPlacement() {
        return true;
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        List<Transport> out = new ArrayList<>();
        for (BoundaryLocs.Loc b : env.boundaryLocs()) {
            if (b.id() == boundaryId) {
                new Cut(b.pos().x(), b.pos().y(), b.direction(), boundaryId)
                        .crossings(
                                out, env.collisionMap(), id, env.world().rules().leftClickWebs());
            }
        }
        return out;
    }

    private record Cut(int x, int z, int dir, int boundaryId) {

        private boolean isWeb() {
            return boundaryId == WebTransport.WEB_ID;
        }

        private void crossings(List<Transport> out, CollisionMap map, String id, boolean clickWebs) {
            Prereq gate = isWeb()
                    ? (clickWebs ? Kit.WEB_CUTTER_ANY_WEAPON : Kit.WEB_CUTTER)
                    : Prereq.all(
                            Prereq.fact(Fact.skillAtLeast(Skill.WOODCUTTING, WOODCUTTING_REQ)),
                            Prereq.fact(Fact.carriedAtLeast(ItemId.MACHETTE.id(), 1)));
            switch (dir) {
                case 0 -> edge(out, x, z - 1, x, z, false, map, gate, id, clickWebs);
                case 1 -> edge(out, x - 1, z, x, z, false, map, gate, id, clickWebs);
                case 2, 3 -> {
                    boolean ns = canCross(map, x, z - 1) && canCross(map, x, z + 1);
                    boolean ew = canCross(map, x - 1, z) && canCross(map, x + 1, z);
                    if (ns) {
                        edge(out, x, z - 1, x, z + 1, true, map, gate, id, clickWebs);
                    }
                    if (ew) {
                        edge(out, x - 1, z, x + 1, z, true, map, gate, id, clickWebs);
                    }
                }
                default -> {}
            }
        }

        private boolean canCross(CollisionMap map, int tx, int ty) {
            return map.inBounds(tx, ty) && map.canStep(x, z, tx, ty, Overlays.NONE);
        }

        private void edge(
                List<Transport> out,
                int ax,
                int ay,
                int bx,
                int by,
                boolean stepThrough,
                CollisionMap map,
                Prereq gate,
                String id,
                boolean clickWebs) {
            if (!walkable(map, ax, ay) || !walkable(map, bx, by)) {
                return;
            }
            if (isWeb()) {
                out.add(new WebTransport(id, x, z, dir, ax, ay, bx, by, gate, WEB_COST, clickWebs));
                out.add(new WebTransport(id, x, z, dir, bx, by, ax, ay, gate, WEB_COST, clickWebs));
                return;
            }
            if (cheapWalkAroundExists(map, ax, ay, bx, by, WALKAROUND_BUDGET)) {
                return;
            }
            out.add(new JungleVineTransport(id, x, z, dir, ax, ay, bx, by, stepThrough, gate, JUNGLE_COST));
            if (stepThrough) {
                out.add(new JungleVineTransport(id, x, z, dir, bx, by, ax, ay, stepThrough, gate, JUNGLE_COST));
            }
        }
    }

    static boolean walkable(CollisionMap map, int x, int y) {
        return map.inBounds(x, y) && !map.fullyBlocked(x, y);
    }

    /**
     * Whether a walk of fewer than {@code budget / 10} cardinal steps, the obstacle left standing,
     * reaches {@code (dx, dy)} from {@code (sx, sy)}.
     */
    static boolean cheapWalkAroundExists(CollisionMap map, int sx, int sy, int dx, int dy, int budget) {
        int maxSteps = budget / 10;
        ArrayDeque<int[]> q = new ArrayDeque<>();
        HashSet<Long> seen = new HashSet<>();
        q.add(new int[] {sx, sy, 0});
        seen.add(key(sx, sy));
        while (!q.isEmpty()) {
            int[] cur = q.poll();
            int cx = cur[0], cy = cur[1], steps = cur[2];
            if (steps >= maxSteps) {
                continue;
            }
            int[][] nb = {{cx, cy - 1}, {cx, cy + 1}, {cx - 1, cy}, {cx + 1, cy}};
            for (int[] n : nb) {
                if (!map.inBounds(n[0], n[1]) || seen.contains(key(n[0], n[1]))) {
                    continue;
                }
                if (!map.canStep(cx, cy, n[0], n[1], Overlays.NONE)) {
                    continue;
                }
                if (n[0] == dx && n[1] == dy) {
                    return true;
                }
                seen.add(key(n[0], n[1]));
                q.add(new int[] {n[0], n[1], steps + 1});
            }
        }
        return false;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }
}
