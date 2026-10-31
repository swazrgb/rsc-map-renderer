package openrsc.gamedata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import openrsc.gamedata.WorldProfile.Feature;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class WorldProfileEventFeaturesTest {

    private static Path confFile(String world) {
        try {
            return ServerConf.resolve().serverDir().resolve(world + ".conf");
        } catch (RuntimeException e) {
            Assumptions.abort("server conf tree not available");
            return null;
        }
    }

    @Test
    void bothWorldsShipEveryEventOff() throws IOException {
        for (String world : new String[] {"uranium", "rsccoleslaw"}) {
            WorldProfile profile = WorldProfile.fromConfFile(confFile(world));
            assertFalse(profile.has(Feature.MICE_TO_MEET_YOU), world);
            assertFalse(profile.has(Feature.A_BONE_TO_PICK), world);
            assertFalse(profile.has(Feature.ARMY_OF_OBSCURITY), world);
        }
    }

    @Test
    void onlyColeslawLoadsTheCustomQuestLocs() throws IOException {
        assertFalse(WorldProfile.fromConfFile(confFile("uranium")).has(Feature.CUSTOM_QUESTS_LOCS));
        assertFalse(WorldProfile.authentic().has(Feature.CUSTOM_QUESTS_LOCS));
        assertTrue(WorldProfile.fromConfFile(confFile("rsccoleslaw")).has(Feature.CUSTOM_QUESTS_LOCS));
    }

    @Test
    void customQuestLocsNeedLocationDataTwoAsWellAsTheSwitch() {
        assertFalse(WorldProfile.builder("custom-quests-on-authentic-locs")
                .locationData(1)
                .enable(Feature.CUSTOM_QUESTS)
                .build()
                .has(Feature.CUSTOM_QUESTS_LOCS));
        assertTrue(WorldProfile.builder("custom")
                .locationData(2)
                .enable(Feature.CUSTOM_QUESTS)
                .build()
                .has(Feature.CUSTOM_QUESTS_LOCS));
        assertFalse(WorldProfile.builder("claims-it")
                .locationData(2)
                .enable(Feature.CUSTOM_QUESTS_LOCS)
                .build()
                .has(Feature.CUSTOM_QUESTS_LOCS));
    }

    @Test
    void overridesTurnAnEventOnOverTheConf() throws IOException {
        WorldProfile events = WorldProfile.fromConfFile(
                confFile("rsccoleslaw"), Map.of("a_bone_to_pick", "true", "army_of_obscurity", "true"));
        assertTrue(events.has(Feature.A_BONE_TO_PICK));
        assertTrue(events.has(Feature.ARMY_OF_OBSCURITY));
        assertFalse(events.has(Feature.MICE_TO_MEET_YOU));
        assertTrue(events.has(Feature.CUSTOM_QUESTS_LOCS));
        assertFalse(WorldProfile.fromConfFile(confFile("rsccoleslaw"), Map.of("want_custom_quests", "false"))
                .has(Feature.CUSTOM_QUESTS_LOCS));
    }
}
