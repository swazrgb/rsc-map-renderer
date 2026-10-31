package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.gamedata.world.CollisionMap;
import openrsc.gamedata.world.LocalOverlay;
import openrsc.nav.NavPolicy;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The walk from Shilo Village's bank onto its wooden gate, past the Undead Ones penned behind its wall. */
class SealedAggressorYieldTest {

    private static final Point POS = Point.of(397, 851);
    private static final Rect GATE = Rect.of(Point.of(394, 851), Point.of(394, 851));
    private static final List<Point> CHUNK = List.of(POS, Point.of(396, 851), Point.of(395, 851), Point.of(394, 851));
    private static final int COMBAT_LEVEL = 93;

    private static CollisionMap map;
    private static NpcDef undead;

    @BeforeAll
    static void load() {
        ServerData env = RealDefs.serverData();
        map = env.collisionMap();
        undead = env.npc(NpcId.UNDEADONE.id());
        assertNotNull(undead);
        assertEquals(3, Berth.aggroRadius(undead), "the premise: an Undead One aggroes from three tiles");
        assertFalse(map.canStep(394, 851, 393, 851), "the premise: a wall between the gate tile and the pen");
        assertNotEquals(
                map.componentId(392, 851), map.componentId(396, 851), "the premise: the pen walks nowhere near");
    }

    @Test
    void an_undead_one_penned_behind_the_wall_lets_the_walk_through() {
        WalkYield.Decision d = decide(sighting(392, 851), Yield.byComponent(map));
        assertEquals(WalkYield.Action.ADVANCE, d.action, "it can never reach the walk");
    }

    @Test
    void read_wall_blind_it_holds_the_walk() {
        WalkYield.Decision d = decide(sighting(392, 851), (a, b, c, e) -> true);
        assertNotEquals(WalkYield.Action.ADVANCE, d.action, "three tiles, two of them its reach");
    }

    @Test
    void an_undead_one_on_the_walkers_side_still_holds_it() {
        WalkYield.Decision d = decide(sighting(396, 848), Yield.byComponent(map));
        assertNotEquals(WalkYield.Action.ADVANCE, d.action, "this one can walk up to us");
    }

    private static WalkYield.Decision decide(Yield.Sighting npc, WalkYield.ConnectCheck connect) {
        Yield y = new Yield();
        List<Yield.Sighting> live = List.of(npc);
        y.roll(live, List.of());
        return y.decide(
                POS,
                GATE,
                CHUNK,
                live,
                List.of(),
                COMBAT_LEVEL,
                false,
                Integer.MAX_VALUE,
                NavPolicy.DEFAULT,
                -1,
                (fx, fy, tx, ty) -> map.canStep(fx, fy, tx, ty),
                (fx, fy, tx, ty) -> map.canReachMob(fx, fy, tx, ty, (LocalOverlay) null),
                connect);
    }

    private static Yield.Sighting sighting(int x, int z) {
        return new Yield.Sighting() {
            @Override
            public int id() {
                return undead.id();
            }

            @Override
            public int serverIndex() {
                return 1;
            }

            @Override
            public boolean inCombat() {
                return false;
            }

            @Override
            public int x() {
                return x;
            }

            @Override
            public int z() {
                return z;
            }

            @Override
            public NpcDef def() {
                return undead;
            }
        };
    }
}
