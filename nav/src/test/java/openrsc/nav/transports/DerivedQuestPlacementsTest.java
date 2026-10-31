package openrsc.nav.transports;

import static openrsc.nav.transports.DerivedArms.arms;
import static openrsc.nav.transports.DerivedArms.placed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DerivedQuestPlacementsTest {

    private static ServerData uranium;
    private static ServerData coleslaw;

    @BeforeAll
    static void load() {
        uranium = RealDefs.serverData();
        coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
    }

    @Test
    @DisplayName("every tile of the Shilo tomb rocks climbs by its own row: y 3619 up, the rest down")
    void shiloCaveRocksAreEveryTile() {
        Map<String, String> expected = Map.of(
                "SHILO_CAVE_ROCKS@348_3617", "[(348, 3616), (349, 3616)] -> (348, 3620)",
                "SHILO_CAVE_ROCKS@349_3617", "[(348, 3616), (349, 3616)] -> (348, 3620)",
                "SHILO_CAVE_ROCKS@348_3619", "[(347, 3620), (348, 3620), (349, 3620)] -> (348, 3616)",
                "SHILO_CAVE_ROCKS@349_3619", "[(348, 3620), (349, 3620)] -> (348, 3616)");
        // The pile's middle row, y 3618, has no tile beside it to click from.
        for (ServerData env : List.of(uranium, coleslaw)) {
            assertEquals(
                    new TreeMap<>(expected),
                    arms(QuestObstacles.SHILO_CAVE_ROCKS, env),
                    env.world().name());
        }
    }

    @Test
    @DisplayName("the statue beside the amulet's landing is a placement too, and bridges nothing")
    void baxtorianStatueBesideTheLandingDeclaresNothing() {
        for (ServerData env : List.of(uranium, coleslaw)) {
            // Every tile beside the statue at (647,3266) already walks to the landing (647,3267).
            Map<String, String> statues = arms(QuestObstacles.BAXTORIAN_STATUE_483_AMULET, env);
            assertEquals(
                    Set.of("BAXTORIAN_STATUE_483_AMULET@665_3270"),
                    statues.keySet(),
                    env.world().name());
            assertTrue(
                    statues.get("BAXTORIAN_STATUE_483_AMULET@665_3270").endsWith("-> (647, 3267)"),
                    env.world().name() + ": " + statues);
        }
    }

    @Test
    @DisplayName("the rowboat and Scot Ruth's tunnel are Coleslaw's placements, and Uranium has none")
    void coleslawOnlyPlacementsAreDerivedOnColeslawOnly() {
        assertEquals(
                Map.of("LUMBRIDGE_ROWBOAT_EDGEVILLE@119_642", "[(119, 644)] -> (206, 449)"),
                arms(SceneryCrossings.LUMBRIDGE_ROWBOAT_EDGEVILLE, coleslaw));
        assertEquals(
                Map.of("SCOT_RUTH_TUNNEL_CHAOS_ALTAR@223_3281", "[(222, 3281)] -> (331, 213)"),
                arms(SceneryCrossings.SCOT_RUTH_TUNNEL_CHAOS_ALTAR, coleslaw));
        assertEquals(Map.of(), arms(SceneryCrossings.LUMBRIDGE_ROWBOAT_EDGEVILLE, uranium));
        assertEquals(Map.of(), arms(SceneryCrossings.SCOT_RUTH_TUNNEL_CHAOS_ALTAR, uranium));
    }

    @Test
    @DisplayName("the metallic gate's Coleslaw twin stays out: its handler's landings are the tomb's")
    void shiloMetallicGateTwinIsNotACrossing() {
        assertTrue(placed(coleslaw, 577, 393, 172), "the premise: Coleslaw places a 577 at (393,172)");
        for (Transport t : AllTransports.defaultRegistry(coleslaw)) {
            assertFalse(
                    Point.of(393, 172).equals(t.sceneryAnchor())
                            && t instanceof DeclarativeTransport d
                            && d.engageSpec().target() instanceof EngageSpec.Target.Scenery s
                            && s.objectId() == 577,
                    "a crossing on the twin: " + t.name());
        }
    }

    @Test
    @DisplayName("the mine door the handler matches by its tile is its stated constant's, not the default's")
    void touristTrapMineDoorSpecialIsExcepted() {
        for (ServerData env : List.of(uranium, coleslaw)) {
            assertEquals(
                    Map.of("TOURIST_TRAP_MINE_DOORS_958_ENTER_MINE@81_801", "[(81, 801), (82, 801)] -> (82, 3630)"),
                    arms(QuestObstacles.TOURIST_TRAP_MINE_DOORS_958_ENTER_MINE, env),
                    env.world().name());
            Map<String, String> exits = arms(QuestObstacles.TOURIST_TRAP_MINE_DOORS_958_EXIT_MINE, env);
            assertFalse(exits.isEmpty(), env.world().name());
            assertTrue(
                    exits.values().stream().allMatch(arm -> arm.endsWith("-> (82, 802)")),
                    env.world().name());
        }
    }

    @Test
    @DisplayName("a family stated one row per placement is now one constant, each placement by its tile")
    void collapsedFamiliesKeepEveryPlacement() {
        Map<TransportDef, Set<String>> expected = Map.of(
                QuestObstacles.HAZEEL_CULT_SEWER_RAFT_BACK,
                Set.of(
                        "HAZEEL_CULT_SEWER_RAFT_BACK@589_3409",
                        "HAZEEL_CULT_SEWER_RAFT_BACK@600_3410",
                        "HAZEEL_CULT_SEWER_RAFT_BACK@621_3409",
                        "HAZEEL_CULT_SEWER_RAFT_BACK@621_3422",
                        "HAZEEL_CULT_SEWER_RAFT_BACK@622_3436",
                        "HAZEEL_CULT_SEWER_RAFT_BACK@622_3464"),
                QuestObstacles.UNDERGROUND_PASS_CAGE_LEVER_801,
                Set.of("UNDERGROUND_PASS_CAGE_LEVER_801@689_3444", "UNDERGROUND_PASS_CAGE_LEVER_801@689_3453"),
                QuestObstacles.DIGSITE_EXPLOSIVE_BRICK_1096,
                Set.of("DIGSITE_EXPLOSIVE_BRICK_1096@13_3337", "DIGSITE_EXPLOSIVE_BRICK_1096@14_3337"),
                QuestObstacles.LEGENDS_QUEST_CRAWL_ROCK_1151,
                Set.of(
                        "LEGENDS_QUEST_CRAWL_ROCK_1151@451_873",
                        "LEGENDS_QUEST_CRAWL_ROCK_1151@452_872",
                        "LEGENDS_QUEST_CRAWL_ROCK_1151@453_873"),
                SceneryCrossings.ENTRANA_SHIP_PORT_SARIM_242,
                Set.of("ENTRANA_SHIP_PORT_SARIM_242@419_571", "ENTRANA_SHIP_PORT_SARIM_242@421_571"),
                SceneryCrossings.ARDOUGNE_WALL_GATEWAY,
                Set.of("ARDOUGNE_WALL_GATEWAY@622_588", "ARDOUGNE_WALL_GATEWAY@624_588"));
        for (ServerData env : List.of(uranium, coleslaw)) {
            expected.forEach((constant, names) -> assertEquals(
                    names, arms(constant, env).keySet(), env.world().name() + " " + constant));
        }
        // The handler picks the side by the player's x, so each half lands on its own far side:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/RandomObjects.java#L162
        assertEquals(
                Map.of(
                        "ARDOUGNE_WALL_GATEWAY@622_588", "[(622, 588)] -> (626, 588)",
                        "ARDOUGNE_WALL_GATEWAY@624_588", "[(624, 588)] -> (620, 589)"),
                arms(SceneryCrossings.ARDOUGNE_WALL_GATEWAY, uranium));
    }
}
