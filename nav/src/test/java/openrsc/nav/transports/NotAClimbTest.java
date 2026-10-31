package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotAClimbTest {

    private record Scripted(int id, int x, int z, String handler) {}

    private static final List<Scripted> URANIUM_SCRIPTED = List.of(
            new Scripted(130, 223, 3385, "ErnestTheChicken: trapdoor, off-by-one landing"),
            new Scripted(41, 316, 546, "Ladders.java:89 Rising Sun Inn, Carol's gate"),
            new Scripted(629, 576, 3529, "Ladders.java:54 fixed landing"),
            new Scripted(621, 605, 3509, "Ladders.java:58 fixed landing"),
            new Scripted(199, 312, 3348, "Ladders.java:221 disk of returning"),
            new Scripted(369, 534, 3371, "TempleOfIkov: stairs up, fixed landing"),
            new Scripted(649, 683, 2396, "GnomeAgilityCourse: the landing, fixed"),
            new Scripted(677, 495, 563, "BarbarianAgilityCourse:113 net, fixed landing"),
            new Scripted(709, 305, 118, "WildernessAgilityCourse: same-floor climb"));

    @Test
    @DisplayName("no generic climb is a scripted ladder or a lateral obstacle that only looks like one")
    void noClimbIsAnException() {
        ServerData env = RealDefs.serverData();
        List<Transport> climbs = Climbs.CLIMB.expand(env);
        for (Scripted s : URANIUM_SCRIPTED) {
            assertTrue(placed(env, s.id(), s.x(), s.z()), "the premise: " + s + " is placed");
            assertFalse(climbsAt(climbs, s.id(), s.x(), s.z()), "a generic climb on " + s);
        }
    }

    @Test
    @DisplayName("Coleslaw's lava forge ladder is the custom quest's, not a generic climb")
    void coleslawsScriptedLadderIsNotAClimb() {
        ServerData env = RealDefs.serverData(RealDefs.COLESLAW);
        assertTrue(placed(env, 5, 329, 3418), "the premise: the forge ladder is placed on Coleslaw");
        assertFalse(climbsAt(Climbs.CLIMB.expand(env), 5, 329, 3418), "Ladders.java:69 scripts it");
    }

    @Test
    @DisplayName("ordinary ladders are generic climbs")
    void ordinaryLaddersAreClimbs() {
        List<Transport> climbs = Climbs.CLIMB.expand(RealDefs.serverData());
        assertTrue(climbsAt(climbs, 5, 102, 532), "a Varrock ladder");
        assertTrue(climbsAt(climbs, 342, 611, 601), "the Ardougne paladins' stairs: guarded, then generic");
    }

    private static boolean placed(ServerData env, int id, int x, int z) {
        for (SceneryLocs.Loc l : env.sceneryLocs()) {
            if (l.id() == id && l.pos().x() == x && l.pos().y() == z) {
                return true;
            }
        }
        return false;
    }

    private static boolean climbsAt(List<Transport> climbs, int id, int x, int z) {
        for (Transport t : climbs) {
            if (((DeclarativeTransport) t).engageSpec().target() instanceof EngageSpec.Target.Scenery s
                    && s.objectId() == id
                    && s.x() == x
                    && s.y() == z) {
                return true;
            }
        }
        return false;
    }
}
