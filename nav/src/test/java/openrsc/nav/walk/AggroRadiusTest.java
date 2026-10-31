package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.ServerRules.NpcBlocking;
import openrsc.gamedata.data.NpcId;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.NavPolicy;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AggroRadiusTest {

    private static final Point POS = Point.of(100, 100);
    private static final Rect GOAL = Rect.of(Point.of(110, 100), Point.of(110, 100));
    private static final List<Point> CHUNK =
            List.of(POS, Point.of(101, 100), Point.of(102, 100), Point.of(103, 100), Point.of(104, 100));

    private static ServerData env;
    private static NpcDef bandit;
    private static NpcDef plain;

    @BeforeAll
    static void load() {
        env = RealDefs.serverData();
        bandit = env.npc(NpcId.BANDIT_AGGRESSIVE.id());
        assertNotNull(bandit);
        assertTrue(bandit.aggressive() && bandit.attackable(), "the premise: the camp bandit aggroes");
        for (int id = 0; id < 900 && plain == null; id++) {
            NpcDef d = env.npc(id);
            if (d != null
                    && d.aggressive()
                    && d.attackable()
                    && d.combatLevel() >= 20
                    && Berth.aggroRadius(d) == Yield.AGGRO_RANGE) {
                plain = d;
            }
        }
        assertNotNull(plain, "an aggressor of the default range");
    }

    @Test
    void theThreeNpcsTheServerGivesTheirOwnRadius() {
        assertEquals(2, Berth.aggroRadius(env.npc(NpcId.BANDIT_AGGRESSIVE.id())));
        assertEquals(10, Berth.aggroRadius(env.npc(NpcId.BLACK_KNIGHT.id())));
        assertEquals(3, Berth.aggroRadius(env.npc(NpcId.UNDEADONE.id())));
        assertEquals(Yield.AGGRO_RANGE, Berth.aggroRadius(plain));
        assertEquals(Yield.AGGRO_RANGE, Berth.aggroRadius(null));
    }

    @Test
    void aBanditTwoTilesFromTheWalkIsAGraze() {
        WalkYield.Decision past = decide(sighting(plain, 103, 102));
        assertEquals(WalkYield.Action.ADVANCE, past.action, "two tiles clears a default aggressor's range");

        WalkYield.Decision bandit2 = decide(sighting(bandit, 103, 102));
        assertNotEquals(WalkYield.Action.ADVANCE, bandit2.action, "a bandit aggroes from two tiles");
    }

    @Test
    void aBanditsRingsStartAtItsOwnRange() {
        Point at = Point.of(100, 100);
        Berth wide = Berth.build(at, List.of(sighting(bandit, 105, 100)), 3, NpcBlocking.NONE);
        Berth narrow = Berth.build(at, List.of(sighting(plain, 105, 100)), 3, NpcBlocking.NONE);

        assertEquals(Berth.BERTH_COST, wide.cost(103, 100), "two tiles: inside a bandit's range");
        assertEquals(Berth.NEAR_COST, narrow.cost(103, 100), "two tiles: one past a default range");
        assertEquals(Berth.FAR_COST, wide.cost(101, 100), "four tiles: the bandit's outer ring");
        assertEquals(0, narrow.cost(101, 100), "four tiles: past a default aggressor's rings");
        assertTrue(wide.inReach(102, 100), "three tiles: in a bandit's range once it steps");
        assertFalse(narrow.inReach(102, 100));
    }

    private static WalkYield.Decision decide(Yield.Sighting npc) {
        Yield y = new Yield();
        List<Yield.Sighting> live = List.of(npc);
        y.roll(live, List.of());
        return y.decide(
                POS,
                GOAL,
                CHUNK,
                live,
                List.of(),
                3,
                false,
                Integer.MAX_VALUE,
                NavPolicy.DEFAULT,
                -1,
                (a, b, c, d) -> true,
                (a, b, c, d) -> true,
                (a, b, c, d) -> true);
    }

    private static Yield.Sighting sighting(NpcDef def, int x, int z) {
        return new Yield.Sighting() {
            @Override
            public int id() {
                return def.id();
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
                return def;
            }
        };
    }
}
