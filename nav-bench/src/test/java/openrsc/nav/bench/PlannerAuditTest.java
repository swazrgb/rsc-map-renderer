package openrsc.nav.bench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import openrsc.gamedata.ServerConf;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.WorldProfile;
import openrsc.gamedata.landscape.LandscapeSource;
import openrsc.nav.Pathfinder;
import openrsc.nav.cache.NavCaches;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlannerAuditTest {

    @Test
    @DisplayName("every planned battery answer is one the walker can do, contract included")
    void everyPlannedAnswerHoldsAtEveryStep() throws Exception {
        ServerConf conf = ServerConf.resolve();
        WorldProfile world = WorldProfile.authentic();
        ServerData env;
        try (LandscapeSource landscape = world.openLandscape(conf)) {
            env = ServerData.load(conf, world, landscape);
        }
        Pathfinder pf = Pathfinder.load(env, NavCaches.none());
        List<String> faults = PlannerGolden.auditBattery(env, pf, 20260914L, 40);
        assertEquals(List.of(), faults, "plans the walker could not have carried out");
    }
}
