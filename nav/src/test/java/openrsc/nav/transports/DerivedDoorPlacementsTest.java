package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DerivedDoorPlacementsTest {

    private static ServerData uranium;
    private static ServerData coleslaw;

    @BeforeAll
    static void load() {
        uranium = RealDefs.serverData();
        coleslaw = RealDefs.serverData(RealDefs.COLESLAW);
    }

    private static Map<String, String> at(TransportDef constant, Point tile, ServerData env) {
        String placement = AllTransports.placementKey(constant.name(), tile);
        Map<String, String> out = new TreeMap<>();
        for (Transport t : constant.expand(env)) {
            if (placement.equals(t.placementKey())) {
                Point e = t.entries().get(0);
                out.put(
                        t.name(),
                        e + " -> " + t.destinationFrom(e) + " | " + t.prereq().description() + " | "
                                + ((DeclarativeTransport) t).engageSpec().describe());
            }
        }
        return out;
    }

    private static void assertOnBothWorlds(Map<String, String> expected, TransportDef constant, Point tile) {
        for (ServerData env : List.of(uranium, coleslaw)) {
            assertEquals(
                    expected,
                    at(constant, tile, env),
                    constant + " on " + env.world().name());
        }
    }

    @Test
    @DisplayName("the door in front of Velrak's cell opens with the jail keys, as the cell door does")
    void velrakFrontDoorIsDerived() {
        // The handler takes every door 83 on y 3425:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/ScorpionCatcher.java#L571
        assertOnBothWorlds(
                Map.of(
                        "DOOR_VELRAK_CELL@360_3425#lowToHigh",
                        "(360, 3424) -> (360, 3425) | carrying ≥1 Jail keys | use item 595 on boundary 83 @(360,3425)",
                        "DOOR_VELRAK_CELL@360_3425#highToLow",
                        "(360, 3425) -> (360, 3424) | carrying ≥1 Jail keys | use item 595 on boundary 83 @(360,3425)"),
                SpecialDoor.DOOR_VELRAK_CELL,
                Point.of(360, 3425));
    }

    @Test
    @DisplayName("Yanille's anvil hut door is one of the four listed picklocks: Thieving 16 in, a click out")
    void yanilleAnvilHutPicklockIsDerived() {
        // The handler lists (581,761) and lets x 582 at y >= 761 through; the door is diagonal, so it
        // crosses from (581,760) to (581,762).
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/thieving/Thieving.java#L581
        assertOnBothWorlds(
                Map.of(
                        "DOOR_THIEVE_PICKLOCK@581_761#lowToHigh",
                        "(581, 760) -> (581, 762) | thieving ≥ 16 | interact(2) with boundary 94 @(581,761)",
                        "DOOR_THIEVE_PICKLOCK@581_761#highToLow",
                        "(581, 762) -> (581, 760) | always | interact with boundary 94 @(581,761)"),
                SpecialDoor.DOOR_THIEVE_PICKLOCK,
                Point.of(581, 761));
    }

    @Test
    @DisplayName("an unlisted picklock door asks Thieving 1 of both sides")
    void anUnlistedPicklockAsksOne() {
        // A door 94 the handler does not list keeps its default level and no free side:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/thieving/Thieving.java#L565
        assertOnBothWorlds(
                Map.of(
                        "DOOR_THIEVE_PICKLOCK@565_586#lowToHigh",
                        "(565, 585) -> (565, 586) | thieving ≥ 1 | interact(2) with boundary 94 @(565,586)",
                        "DOOR_THIEVE_PICKLOCK@565_586#highToLow",
                        "(565, 586) -> (565, 585) | thieving ≥ 1 | interact(2) with boundary 94 @(565,586)"),
                SpecialDoor.DOOR_THIEVE_PICKLOCK,
                Point.of(565, 586));
    }

    @Test
    @DisplayName("the second loose fence panel on y 705 pushes through like the first")
    void secondLooseFencePanelIsDerived() {
        // The handler takes every fence 101 on y 705:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/TreeGnomeVillage.java#L850
        assertOnBothWorlds(
                Map.of(
                        "LOOSE_FENCE_TGV@634_705#lowToHigh",
                        "(634, 704) -> (634, 705) | always | interact with boundary 101 @(634,705)",
                        "LOOSE_FENCE_TGV@634_705#highToLow",
                        "(634, 705) -> (634, 704) | always | interact with boundary 101 @(634,705)"),
                SpecialDoor.LOOSE_FENCE_TGV,
                Point.of(634, 705));
    }

    @Test
    @DisplayName("the dig site's tent door is a tent door like Al Shabim's")
    void digSiteTentDoorIsDerived() {
        // The handler takes every tent door 196:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/touristtrap/TouristTrap.java#L3210
        assertOnBothWorlds(
                Map.of(
                        "TENT_DOOR_AL_SHABIM@9_537#lowToHigh",
                        "(8, 537) -> (9, 537) | always | interact with boundary 196 @(9,537)",
                        "TENT_DOOR_AL_SHABIM@9_537#highToLow",
                        "(9, 537) -> (8, 537) | always | interact with boundary 196 @(9,537)"),
                SpecialDoor.TENT_DOOR_AL_SHABIM,
                Point.of(9, 537));
    }

    @Test
    @DisplayName("a side test is read at every placement: the chamber of fear is free to leave by either door")
    void fearRoomSidesAreReadPerPlacement() {
        // y 3335..3341 is inside the room, whichever door is left by:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L427
        Map<String, String> south = at(SpecialDoor.DOOR_TEMPLE_IKOV_FEAR_ROOM, Point.of(533, 3335), uranium);
        Map<String, String> north = at(SpecialDoor.DOOR_TEMPLE_IKOV_FEAR_ROOM, Point.of(533, 3342), uranium);
        assertEquals(
                "(533, 3335) -> (533, 3334) | always | interact with boundary 104 @(533,3335)",
                south.get("DOOR_TEMPLE_IKOV_FEAR_ROOM@533_3335#highToLow"));
        assertEquals(
                "(533, 3341) -> (533, 3342) | always | interact with boundary 104 @(533,3342)",
                north.get("DOOR_TEMPLE_IKOV_FEAR_ROOM@533_3342#lowToHigh"));
        assertEquals(
                "(533, 3334) -> (533, 3335) | wearing Pendant of lucien | interact with boundary 104 @(533,3335)",
                south.get("DOOR_TEMPLE_IKOV_FEAR_ROOM@533_3335#lowToHigh"));
    }

    @Test
    @DisplayName("the doors a handler opens free from one side are free from that side")
    void freeSidesFollowTheServer() {
        // Bravek's door opens from x < 648:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L632
        // the plague house's doors from y 606..611:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/DoorAction.java#L563
        // the mourners' gate from x <= 630:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/BioHazard.java#L1155
        assertEquals(
                "(647, 585) -> (648, 585) | always | interact with boundary 121 @(648,585)",
                at(SpecialDoor.DOOR_TO_BRAVEK, Point.of(648, 585), uranium).get("DOOR_TO_BRAVEK@648_585#lowToHigh"));
        assertEquals(
                "(637, 606) -> (637, 605) | always | interact with boundary 123 @(637,606)",
                at(SpecialDoor.DOOR_INFECTED_ELENA, Point.of(637, 606), uranium)
                        .get("DOOR_INFECTED_ELENA@637_606#highToLow"));
        assertEquals(
                "(635, 611) -> (635, 612) | always | interact with boundary 123 @(635,612)",
                at(SpecialDoor.DOOR_INFECTED_ELENA, Point.of(635, 612), uranium)
                        .get("DOOR_INFECTED_ELENA@635_612#lowToHigh"));
        assertEquals(
                "(630, 1514) -> (631, 1514) | always | interact with scenery 504 @(631,1514)",
                at(SpecialDoor.GATE_MOURNERS_HOUSE, Point.of(631, 1514), uranium)
                        .get("GATE_MOURNERS_HOUSE@631_1514#lowToHigh"));
    }
}
