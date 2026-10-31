package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.bot.api.Nullable;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules.NpcBlocking;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class NpcBlockingWalkTest {

    // Low enough for every aggressive NPC to attack it.
    private static final int COMBAT_LEVEL = 3;

    private static CollisionMap map;
    private static Point from;
    private static Point to;
    private static Npc npc;

    private record Npc(int x, int z, NpcDef def) implements Berth.Sighting {}

    @BeforeAll
    static void load() {
        ServerData env = RealDefs.serverData();
        map = env.collisionMap();
        StepView plain = StepView.ofMap(map, Planner.DangerCharge.NONE);
        for (ServerData.NpcSpawn s : env.npcSpawns()) {
            NpcDef d = env.npc(s.id());
            if (d == null || !d.aggressive() || !d.attackable() || d.combatLevel() * 2 + 1 <= COMBAT_LEVEL) {
                continue;
            }
            boolean open = true;
            for (int dz = -3; dz <= 3 && open; dz++) {
                Point a = Point.of(s.startX() - 6, s.startZ() + dz);
                Point b = Point.of(s.startX() + 6, s.startZ() + dz);
                open = !map.fullyBlocked(a.x(), a.y()) && WindowSearch.line(a, b, plain, Rect.of(b, b), true) != null;
            }
            if (open) {
                npc = new Npc(s.startX(), s.startZ(), d);
                from = Point.of(s.startX() - 6, s.startZ());
                to = Point.of(s.startX() + 6, s.startZ());
                break;
            }
        }
        assertNotNull(npc, "an aggressive spawn on open ground");
    }

    private static @Nullable List<Point> walk(NpcBlocking rule, boolean berthOn) {
        Berth berth = Berth.build(from, List.of(npc), COMBAT_LEVEL, rule);
        StepView view = StepView.amongNpcs(map, Planner.DangerCharge.NONE, berth, berthOn);
        return new WindowSearch(map).run(view, from, Rect.of(to, to), null, null, true, WindowSearch.Momentum.NONE);
    }

    private static int nearest(List<Point> path) {
        int best = Integer.MAX_VALUE;
        for (Point p : path) {
            best = Math.min(best, Point.chebyshev(p.x(), p.y(), npc.x(), npc.z()));
        }
        return best;
    }

    // Uranium's npc_blocking is 2: every aggressive NPC blocks.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/uranium.conf#L200
    @Test
    void uraniumWalksRoundTheAggressorsTile() {
        List<Point> path = walk(NpcBlocking.AGGRESSIVE, false);
        assertNotNull(path, "a way round");
        assertFalse(path.contains(Point.of(npc.x(), npc.z())), "the server refuses the step onto it: " + path);
    }

    // Coleslaw's npc_blocking is 0: no NPC blocks.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/rsccoleslaw.conf#L202
    @Test
    void coleslawWalksOverTheAggressorsTile() {
        List<Point> path = walk(NpcBlocking.NONE, false);
        assertNotNull(path);
        assertTrue(path.contains(Point.of(npc.x(), npc.z())), "no NPC blocks, so the straight way: " + path);
    }

    @Test
    void coleslawStillBerthsTheAggressor() {
        List<Point> path = walk(NpcBlocking.NONE, true);
        assertNotNull(path);
        assertTrue(nearest(path) >= 2, "it still attacks, so the walk keeps out of its reach: " + path);
    }
}
