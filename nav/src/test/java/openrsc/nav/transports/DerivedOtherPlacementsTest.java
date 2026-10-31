package openrsc.nav.transports;

import static openrsc.nav.transports.DerivedArms.arms;
import static openrsc.nav.transports.DerivedArms.placed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.ObjectTelePoints;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DerivedOtherPlacementsTest {

    private static ServerData uranium;
    private static ServerData coleslaw;

    @BeforeAll
    static void load() {
        uranium = RealDefs.serverData();
        coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
    }

    @Test
    @DisplayName("Brimstail's southern cave mouth is the same crossing as the one the row was written for")
    void brimstailsSouthernCaveMouthIsDerived() {
        // The handler lands every cave 667 on (730,3334):
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/npcs/grandtree/Brimstail.java#L40
        for (ServerData env : List.of(uranium, coleslaw)) {
            Map<String, String> caves = arms(LadderSpecials.BRIMSTAIL_CAVE_STRONGHOLD, env);
            assertEquals(
                    "[(696, 634)] -> (730, 3334)",
                    caves.get("BRIMSTAIL_CAVE_STRONGHOLD@694_632"),
                    env.world().name() + ": " + caves);
            assertTrue(
                    caves.containsKey("BRIMSTAIL_CAVE_STRONGHOLD@731_501"),
                    env.world().name() + ": " + caves);
        }
    }

    @Test
    @DisplayName("every Lady Lumbridge ladder leaves for Port Sarim; the two a Crandor boarding lands by, for Crandor")
    void shipLaddersLandByLumbLady() {
        // Every ship's ladder lands by where the ship was boarded:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/DragonSlayer.java#L367
        // and a Crandor boarding puts the player by the two at y 3473:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/free/DragonSlayer.java#L376
        for (ServerData env : List.of(uranium, coleslaw)) {
            assertEquals(
                    Set.of("@259_3473", "@259_3494", "@281_3473", "@281_3494"),
                    placements(LadderSpecials.LADY_LUMBRIDGE_LEAVE_SHIP_LADDER, env),
                    env.world().name());
            assertEquals(
                    Set.of("@259_3473", "@281_3473"),
                    placements(LadderSpecials.LADY_LUMBRIDGE_LEAVE_SHIP_LADDER_CRANDOR, env),
                    env.world().name());
            assertTrue(arms(LadderSpecials.LADY_LUMBRIDGE_LEAVE_SHIP_LADDER, env).values().stream()
                    .allMatch(a -> a.endsWith("-> (259, 641)")));
            assertTrue(arms(LadderSpecials.LADY_LUMBRIDGE_LEAVE_SHIP_LADDER_CRANDOR, env).values().stream()
                    .allMatch(a -> a.endsWith("-> (409, 638)")));
        }
    }

    private static Set<String> placements(TransportDef constant, ServerData env) {
        Set<String> out = new HashSet<>();
        for (String name : arms(constant, env).keySet()) {
            int arm = name.indexOf('#');
            out.add(name.substring(name.indexOf('@'), arm < 0 ? name.length() : arm));
        }
        return out;
    }

    @Test
    @DisplayName("the White Wolf Mountain stairs the handler checks by tile stay the stated special's")
    void whiteWolfStairsSpecialWinsItsTile() {
        // (426,458) leads east, every other placement west:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/FishingContest.java#L712
        for (ServerData env : List.of(uranium, coleslaw)) {
            assertEquals(
                    Set.of("WHITE_WOLF_MOUNTAIN_STAIRS_DOWN@385_466"),
                    arms(LadderSpecials.WHITE_WOLF_MOUNTAIN_STAIRS_DOWN, env).keySet(),
                    env.world().name());
            Map<String, String> east = arms(LadderSpecials.WHITE_WOLF_MOUNTAIN_STAIRS_DOWN_E, env);
            assertFalse(east.isEmpty(), env.world().name());
            assertTrue(
                    east.values().stream().allMatch(a -> a.endsWith("-> (426, 3294)")),
                    env.world().name());
        }
    }

    @Test
    @DisplayName("no generic climb doubles a placement a scripted climb owns")
    void noGenericClimbOnAScriptedPlacement() {
        for (ServerData env : List.of(uranium, coleslaw)) {
            Set<Point> scripted = new HashSet<>();
            for (LadderSpecials l : LadderSpecials.values()) {
                for (Transport t : l.expand(env)) {
                    scripted.add(t.sceneryAnchor());
                }
            }
            for (Transport climb : Climbs.CLIMB.expand(env)) {
                assertFalse(
                        scripted.contains(climb.sceneryAnchor()),
                        env.world().name() + ": " + climb.name() + " climbs a scripted placement");
            }
        }
    }

    @Test
    @DisplayName("no telepoint stands on a placement Ladders.java scripts after its telepoint lookup")
    void noTelePointPreemptsAnIdMatchedBranch() {
        // The tele point is looked up before these ids' own branches:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L111
        Set<Integer> afterTelePoints = Set.of(487, 488, 349, 348, 776, 199);
        for (ServerData env : List.of(uranium, coleslaw)) {
            Set<Point> telePoints = new HashSet<>();
            for (ObjectTelePoints.Point p : env.objectTelePoints()) {
                telePoints.add(Point.of(p.x(), p.y()));
            }
            int placed = 0;
            for (SceneryLocs.Loc loc : env.sceneryLocs()) {
                if (afterTelePoints.contains(loc.id())) {
                    placed++;
                    assertFalse(
                            telePoints.contains(
                                    Point.of(loc.pos().x(), loc.pos().y())),
                            env.world().name() + ": a telepoint on " + loc);
                }
            }
            assertEquals(6, placed, env.world().name() + ": the premise, one placement of each");
        }
    }
}
