package openrsc.gamedata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DataViewsTest {

    @Test
    void npcRespawnScaleIsTheWorldsMultiplierWhateverItsTick() throws Exception {
        ServerConf conf = ServerConf.resolve();
        assertEquals(1.0, DataViews.npcRespawnScale(conf, WorldProfile.authentic()), 1e-9);
        assertEquals(1.0, DataViews.npcRespawnScale(conf, WorldProfile.fromConf(conf, "uranium")), 1e-9);
        assertEquals(0.7, DataViews.npcRespawnScale(conf, WorldProfile.fromConf(conf, "rsccoleslaw")), 1e-9);
    }

    @Test
    void npcRespawnSecondsIsZeroOnlyForNever() {
        assertEquals(0, DataViews.respawnSeconds(0, 0.47));
        assertEquals(1, DataViews.respawnSeconds(1, 0.7));
        assertEquals(57, DataViews.respawnSeconds(82, 0.7));
    }
}
