package openrsc.nav.walk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.NpcDef;
import openrsc.gamedata.geometry.Point;
import openrsc.gamedata.geometry.Rect;
import openrsc.nav.NavPolicy;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class YieldFightGraceTest {

    private static final Point POS = Point.of(100, 100);
    private static final Rect GOAL = Rect.of(Point.of(110, 100), Point.of(110, 100));
    private static final List<Point> CHUNK =
            List.of(POS, Point.of(101, 100), Point.of(102, 100), Point.of(103, 100), Point.of(104, 100));

    private static Yield.Sighting aggressor;

    @BeforeAll
    static void load() {
        ServerData env = RealDefs.serverData();
        NpcDef def = null;
        for (int id = 0; id < 900 && def == null; id++) {
            NpcDef d = env.npc(id);
            if (d != null && d.aggressive() && d.attackable() && d.combatLevel() >= 60) {
                def = d;
            }
        }
        assertNotNull(def, "an aggressive definition to stand beside the walk");
        aggressor = sighting(def, 102, 101);
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

    private static WalkYield.Decision tick(Yield y, boolean inCombat, int sinceRetreat) {
        List<Yield.Sighting> live = List.of(aggressor);
        y.roll(live, List.of());
        return y.decide(
                POS,
                GOAL,
                CHUNK,
                live,
                List.of(),
                3,
                inCombat,
                sinceRetreat,
                NavPolicy.DEFAULT,
                -1,
                (a, b, c, d) -> true,
                (a, b, c, d) -> true,
                (a, b, c, d) -> true);
    }

    @Test
    void aBotThatNeverFoughtIsNotInAFightsGrace() {
        Yield y = new Yield();
        for (int i = 0; i < 10; i++) {
            tick(y, false, Integer.MAX_VALUE);
            assertNotEquals("RAN_AWAY", y.branch(), "tick " + i);
        }
    }

    @Test
    void aFightThatEndsWithoutARetreatLeavesNoGrace() {
        Yield y = new Yield();
        for (int i = 0; i < 4; i++) {
            tick(y, true, Integer.MAX_VALUE);
            assertEquals("FIGHTING", y.branch());
        }
        WalkYield.Decision after = tick(y, false, Integer.MAX_VALUE);
        assertNotEquals("RAN_AWAY", y.branch(), "a won fight leaves the next aggro check free to fire");
        assertNotEquals(WalkYield.Action.ADVANCE, after.action, "so the graze beside the walk is yielded to at once");
    }

    @Test
    void ourOwnRetreatCoversTheStepsTheNpcsJudgeWithinFiveTicks() {
        Yield y = new Yield();
        tick(y, true, Integer.MAX_VALUE);
        for (int since = 0; since <= 3; since++) {
            WalkYield.Decision d = tick(y, false, since);
            assertEquals("RAN_AWAY", y.branch(), since + " ticks after the retreat");
            assertEquals(WalkYield.Action.ADVANCE, d.action);
        }
        WalkYield.Decision judged = tick(y, false, 4);
        assertNotEquals("RAN_AWAY", y.branch(), "a walk sent now is judged on the sixth tick, past the wait");
        assertNotEquals(WalkYield.Action.ADVANCE, judged.action);
    }
}
