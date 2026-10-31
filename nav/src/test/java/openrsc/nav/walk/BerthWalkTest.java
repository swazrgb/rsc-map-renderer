package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.bot.api.eligibility.AccountFacts;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.LoadoutSpec;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Planner;
import openrsc.nav.RealDefs;
import openrsc.nav.probe.NavProbe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BerthWalkTest {

    private static ServerData env;
    private static Pathfinder pf;
    private static Point a;
    private static Point b;
    private static Yield.Sighting wolf;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        pf = RealDefs.pathfinder();
        NpcDef def = null;
        for (int id = 0; id < 900 && def == null; id++) {
            NpcDef d = env.npc(id);
            // Aggressive to the fresh account the walk is planned for.
            if (d != null && d.aggressive() && d.attackable() && d.combatLevel() >= 60) {
                def = d;
            }
        }
        assertNotNull(def, "an aggressive definition to stand on the way");
        NpcDef aggressor = def;
        StepView plain = StepView.ofMap(env.collisionMap(), Planner.DangerCharge.NONE);
        outer:
        for (int z = 560; z < 760; z += 3) {
            for (int x = 60; x < 330; x += 3) {
                boolean open = true;
                for (int dz = -3; dz <= 3 && open; dz++) {
                    Point p = Point.of(x, z + dz);
                    Point q = Point.of(x + 30, z + dz);
                    open = !env.collisionMap().fullyBlocked(p.x(), p.y())
                            && WindowSearch.line(p, q, plain, Rect.of(q, q), true) != null;
                }
                if (open) {
                    a = Point.of(x, z);
                    b = Point.of(x + 30, z);
                    break outer;
                }
            }
        }
        assertNotNull(a, "an open march with room either side");
        Point mid = Point.of(a.x() + 15, a.y());
        wolf = new Yield.Sighting() {
            @Override
            public int id() {
                return aggressor.id();
            }

            @Override
            public int serverIndex() {
                return 0;
            }

            @Override
            public boolean inCombat() {
                return false;
            }

            @Override
            public int x() {
                return mid.x();
            }

            @Override
            public int z() {
                return mid.y();
            }

            @Override
            public NpcDef def() {
                return aggressor;
            }
        };
    }

    @Test
    @DisplayName("an aggressor on the way is walked round, its chase reach kept clear")
    void anAggressorIsBerthed() {
        NavPolicy policy = NavPolicy.DEFAULT.withDanger(NavPolicy.DangerAvoidance.OFF);
        AccountFacts fresh = NavProbe.onFootFacts(env, a.x(), a.y());
        List<NavProbe.ProbeStep> blind = NavProbe.planBetween(
                pf, a.x(), a.y(), b.x(), b.y(), fresh, policy, LoadoutSpec.any(), new NavProbe.SimulationStats(), null);
        assertTrue(nearest(blind) <= 1, "the premise: without the NPC the walk passes through it");

        NavProbe.Crowd crowd = (tick, pos, radius) ->
                Point.pythagoras(pos.x(), pos.y(), wolf.x(), wolf.z()) <= radius ? List.of(wolf) : List.of();
        List<NavProbe.ProbeStep> berthed = NavProbe.planBetween(
                pf,
                a.x(),
                a.y(),
                b.x(),
                b.y(),
                fresh,
                policy,
                LoadoutSpec.any(),
                new NavProbe.SimulationStats(),
                crowd);
        NavProbe.ProbeStep last = berthed.get(berthed.size() - 1);
        assertEquals(b, Point.of(last.x(), last.y()), "arrives");
        assertTrue(nearest(berthed) >= 2, "never inside the aggressor's chase reach: " + berthed);
    }

    private static int nearest(List<NavProbe.ProbeStep> walk) {
        int best = Integer.MAX_VALUE;
        for (NavProbe.ProbeStep s : walk) {
            best = Math.min(best, Point.chebyshev(s.x(), s.y(), wolf.x(), wolf.z()));
        }
        return best;
    }
}
