package openrsc.nav.transports.spec;

import static openrsc.nav.transports.spec.EveryPlacement.everyScenery;
import static openrsc.nav.transports.spec.Rows.from;
import static openrsc.nav.transports.spec.Rows.to;
import static openrsc.nav.transports.spec.SceneryCrossing.scenery;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import openrsc.nav.transports.AllTransports;
import org.junit.jupiter.api.Test;

class EveryPlacementTest {

    private static final int BARREL = 1070;

    @Test
    void everyPlacementIsARowAndNamedByItsTile() {
        ServerData env = RealDefs.serverData();
        EveryPlacement every =
                everyScenery(BARREL, p -> List.of(scenery(BARREL, p.at(), to(550, 711), 30, Rows.clickAt(p.at()))));
        List<Transport> arms = AllTransports.namedByPlacement("BARREL", every.expand(env, "BARREL"));
        Set<String> names = new TreeSet<>();
        for (Transport t : arms) {
            names.add(t.name());
            assertEquals(t.sceneryAnchor(), t.entries().get(0), "fired from its own barrel: " + t.name());
        }
        assertEquals(14, names.size(), "one transport per barrel: " + names); // Uranium places 14
        assertTrue(names.contains("BARREL@250_755"), names.toString());
    }

    @Test
    void anExceptedPlacementIsLeftToItsStatedRow() {
        ServerData env = RealDefs.serverData();
        EveryPlacement every = everyScenery(
                        BARREL, p -> List.of(scenery(BARREL, p.at(), to(550, 711), 30, Rows.clickAt(p.at()))))
                .except(Point.of(250, 755), Point.of(254, 758));
        for (Transport t : every.expand(env, "BARREL")) {
            assertTrue(
                    !t.sceneryAnchor().equals(Point.of(250, 755))
                            && !t.sceneryAnchor().equals(Point.of(254, 758)),
                    "an excepted barrel: " + t.sceneryAnchor());
        }
        assertEquals(12, every.expand(env, "BARREL").size());
    }

    @Test
    void aRowThatClicksAnotherTileThrows() {
        ServerData env = RealDefs.serverData();
        // With no clickAt the object is taken to stand on the entry tile, (1,1), not on the placement.
        EveryPlacement stray = everyScenery(BARREL, p -> List.of(scenery(BARREL, from(1, 1), to(550, 711), 30)));
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> stray.expand(env, "STRAY"));
        assertTrue(e.getMessage().startsWith("STRAY: the row for 1070 @"), e.getMessage());
    }
}
