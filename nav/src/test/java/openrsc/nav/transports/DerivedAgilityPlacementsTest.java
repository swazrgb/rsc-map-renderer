package openrsc.nav.transports;

import static openrsc.nav.transports.DerivedArms.arms;
import static openrsc.nav.transports.DerivedArms.placed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DerivedAgilityPlacementsTest {

    private static List<ServerData> worlds;

    @BeforeAll
    static void load() {
        worlds = List.of(RealDefs.serverData(), RealDefs.serverData(RealDefs.COLESLAW));
    }

    @Test
    @DisplayName("the second rock 760, facing 6, lands each side where doRock puts it")
    void secondRock760() {
        for (ServerData env : worlds) {
            assertEquals(
                    Map.of(
                            "UNDERGROUND_PASS_ROCK_760@681_3459#680_3459", "[(680, 3459)] -> (681, 3460)",
                            "UNDERGROUND_PASS_ROCK_760@681_3459#682_3459", "[(682, 3459)] -> (682, 3460)"),
                    arms(AgilityObstacles.UNDERGROUND_PASS_ROCK_760, env, Point.of(681, 3459)));
        }
    }

    @Test
    @DisplayName("the spear rocks' second lane is disarmed across like the first")
    void spearRockSecondLane() {
        for (ServerData env : worlds) {
            assertEquals(
                    Map.of(
                            "UNDERGROUND_PASS_SPEAR_ROCK_806@695_3449#694_3448",
                            "[(694, 3448), (694, 3449)] -> (696, 3449)",
                            "UNDERGROUND_PASS_SPEAR_ROCK_806@695_3449#696_3448",
                            "[(696, 3448), (696, 3449)] -> (694, 3449)"),
                    arms(AgilityObstacles.UNDERGROUND_PASS_SPEAR_ROCK_806, env, Point.of(695, 3449)));
            assertEquals(
                    Map.of(
                            "UNDERGROUND_PASS_SPEAR_ROCK_811@715_3448#714_3447",
                            "[(714, 3447), (714, 3448)] -> (716, 3448)",
                            "UNDERGROUND_PASS_SPEAR_ROCK_811@715_3448#716_3447",
                            "[(716, 3447), (716, 3448)] -> (714, 3448)"),
                    arms(AgilityObstacles.UNDERGROUND_PASS_SPEAR_ROCK_811, env, Point.of(715, 3448)));
        }
    }

    @Test
    @DisplayName("every tile of a stalactite takes the rope to its id's landing")
    void stalactiteTiles() {
        for (ServerData env : worlds) {
            assertEquals(
                    Map.of(
                            "UNDERGROUND_PASS_STALACTITE_771_ROPE@697_3435",
                            "[(698, 3435), (698, 3436)] -> (695, 3435)"),
                    arms(AgilityObstacles.UNDERGROUND_PASS_STALACTITE_771_ROPE, env, Point.of(697, 3435)));
            assertEquals(
                    Map.of(
                            "UNDERGROUND_PASS_STALACTITE_799_ROPE@679_3435",
                            "[(678, 3434), (678, 3435)] -> (682, 3436)"),
                    arms(AgilityObstacles.UNDERGROUND_PASS_STALACTITE_799_ROPE, env, Point.of(679, 3435)));
        }
    }

    @Test
    @DisplayName("the blessed spider swamp's west tile, which the first throw deletes, is never a crossing")
    void swampWestTileIsLeftOut() {
        for (ServerData env : worlds) {
            // The first throw spawns rocks onto (714,3418), which deletes the swamp placed there for good:
            // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/undergroundpass/mechanism/UndergroundPassMechanismMap1.java#L141
            assertTrue(placed(env, 795, 714, 3418), "the premise: the west swamp tile is placed");
            assertEquals(
                    Map.of(),
                    arms(
                            AgilityObstacles.UNDERGROUND_PASS_BLESSED_SPIDER_SWAMP_795_USE_ROCKS,
                            env,
                            Point.of(714, 3418)));
            assertEquals(
                    2,
                    arms(AgilityObstacles.UNDERGROUND_PASS_BLESSED_SPIDER_SWAMP_795_USE_ROCKS, env, Point.of(715, 3418))
                            .size(),
                    "the east tile still crosses both ways");
        }
    }

    @Test
    @DisplayName("the Shilo rocks climb up from the x-450 column and down from the other")
    void shiloRocksByColumn() {
        for (ServerData env : worlds) {
            assertEquals(
                    Map.of("SHILO_ROCKS_CLIMB@450_827", "[(449, 828)] -> (452, 829)"),
                    arms(AgilityObstacles.SHILO_ROCKS_CLIMB, env, Point.of(450, 827)));
            assertEquals(
                    Map.of("SHILO_ROCKS_CLIMB@451_827", "[(452, 827), (452, 828)] -> (449, 828)"),
                    arms(AgilityObstacles.SHILO_ROCKS_CLIMB, env, Point.of(451, 827)));
        }
    }

    @Test
    @DisplayName("the Barbarian ledge balances only from its x-498 tile, as the handler checks")
    void barbarianLedgeOnlyAtX498() {
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/agility/BarbarianAgilityCourse.java#L120
        for (ServerData env : worlds) {
            assertTrue(
                    arms(AgilityObstacles.BARBARIAN_AGILITY_LEDGE_678_BALANCE_ACROSS, env, Point.of(499, 1506))
                            .isEmpty(),
                    "the ledge's east tile does nothing (BarbarianAgilityCourse.java:120)");
            assertEquals(
                    List.of("BARBARIAN_AGILITY_LEDGE_678_BALANCE_ACROSS@498_1506"),
                    List.copyOf(
                            arms(AgilityObstacles.BARBARIAN_AGILITY_LEDGE_678_BALANCE_ACROSS, env, Point.of(498, 1506))
                                    .keySet()));
        }
    }
}
