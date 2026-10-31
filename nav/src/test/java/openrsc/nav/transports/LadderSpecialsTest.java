package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.world.CollisionMap;
import openrsc.nav.RealDefs;
import org.junit.jupiter.api.Test;

class LadderSpecialsTest {

    @Test
    void theBlackHolesRandomLandingWalksNowhere() {
        // The server lands the player on a random tile of this 5x5, and the one declared landing stands
        // for all of them only while none walks anywhere:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L230
        for (ServerData env : List.of(RealDefs.serverData(), RealDefs.serverData(RealDefs.COLESLAW))) {
            CollisionMap map = env.collisionMap();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    assertEquals(
                            0,
                            map.componentId(305 + dx, 3300 + dz),
                            env.world().name() + " (" + (305 + dx) + "," + (3300 + dz) + ")");
                }
            }
        }
    }
}
