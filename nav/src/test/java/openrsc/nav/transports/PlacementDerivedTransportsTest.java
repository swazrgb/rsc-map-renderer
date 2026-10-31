package openrsc.nav.transports;

import static openrsc.nav.transports.DerivedArms.arms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import openrsc.gamedata.ServerData;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlacementDerivedTransportsTest {

    private static ServerData uranium;
    private static ServerData coleslaw;

    @BeforeAll
    static void load() {
        uranium = RealDefs.serverData();
        coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
    }

    @Test
    @DisplayName("Death Island's magic portal leaves for the portal's landing, on Coleslaw only")
    void deathIslandPortalIsDerivedOnColeslaw() {
        Map<String, String> portals = arms(WallCrossings.MAGIC_PORTAL_149, coleslaw);
        assertEquals(
                "[(971, 165), (972, 165)] -> (362, 1515)",
                portals.get("MAGIC_PORTAL_149@972_165"),
                "the island's north portal tile: " + portals);
        assertEquals(
                "[(971, 166), (972, 166)] -> (362, 1515)",
                portals.get("MAGIC_PORTAL_149@972_166"),
                "the island's south portal tile: " + portals);
        assertTrue(
                arms(WallCrossings.MAGIC_PORTAL_149, uranium).keySet().stream().noneMatch(n -> n.contains("@972_")),
                "Uranium loads no custom-quest boundaries, so it has no island portal");
    }

    @Test
    @DisplayName("the Wizards' Guild portals are the same six crossings on both worlds")
    void wizardsGuildPortalsAreUnchanged() {
        Map<String, String> expected = new TreeMap<>(Map.of(
                "MAGIC_PORTAL_147@602_2642", "[(602, 2641), (602, 2642)] -> (212, 695)",
                "MAGIC_PORTAL_147@603_2642", "[(603, 2641), (603, 2642)] -> (212, 695)",
                "MAGIC_PORTAL_148@599_2645", "[(598, 2645), (599, 2645)] -> (511, 1452)",
                "MAGIC_PORTAL_148@599_2646", "[(598, 2646), (599, 2646)] -> (511, 1452)",
                "MAGIC_PORTAL_149@602_2650", "[(602, 2649), (602, 2650)] -> (362, 1515)",
                "MAGIC_PORTAL_149@603_2650", "[(603, 2649), (603, 2650)] -> (362, 1515)"));
        for (ServerData env : List.of(uranium, coleslaw)) {
            Map<String, String> guild = new TreeMap<>();
            for (WallCrossings portal : List.of(
                    WallCrossings.MAGIC_PORTAL_147, WallCrossings.MAGIC_PORTAL_148, WallCrossings.MAGIC_PORTAL_149)) {
                arms(portal, env).forEach((name, arm) -> {
                    if (!name.contains("@972_")) {
                        guild.put(name, arm);
                    }
                });
            }
            assertEquals(expected, guild, env.world().name());
        }
    }

    @Test
    @DisplayName("Death Island's farmhouse ladder is a generic climb, on Coleslaw only")
    void deathIslandLadderIsDerivedOnColeslaw() {
        List<String> island = Climbs.CLIMB.expand(coleslaw).stream()
                .map(Transport::name)
                .filter(n -> n.equals("CLIMB@973_162") || n.equals("CLIMB@973_1106"))
                .toList();
        assertEquals(List.of("CLIMB@973_162", "CLIMB@973_1106"), island);
        assertTrue(Climbs.CLIMB.expand(uranium).stream().noneMatch(t -> t.name().startsWith("CLIMB@973_")));
    }

    @Test
    @DisplayName("a placement a scripted climb states by its tile is the script's, not a generic climb")
    void aScriptedPlacementWinsItsTile() {
        // The server scripts the stairs 41 at (316,546) by their tile:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L89
        assertTrue(
                Climbs.CLIMB.expand(uranium).stream().noneMatch(t -> t.name().equals("CLIMB@316_546")),
                "the inn's stairs are no generic climb");
        assertEquals(1, LadderSpecials.RISING_SUN_INN_UPSTAIRS.expand(uranium).size(), "they are the special's");
        assertTrue(
                Climbs.CLIMB.expand(uranium).stream().anyMatch(t -> t.name().equals("CLIMB@358_492")),
                "another 41 is a generic climb");
    }
}
