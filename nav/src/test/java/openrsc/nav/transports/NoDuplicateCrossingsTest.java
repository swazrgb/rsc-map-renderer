package openrsc.nav.transports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.RealDefs;
import openrsc.nav.Transport;
import org.junit.jupiter.api.Test;

class NoDuplicateCrossingsTest {

    @Test
    void uraniumDeclaresEachCrossingOnce() {
        assertEquals(Map.of(), duplicates(RealDefs.serverData()));
    }

    @Test
    void coleslawDeclaresEachCrossingOnce() {
        assertEquals(Map.of(), duplicates(RealDefs.serverData(RealDefs.COLESLAW)));
    }

    private static Map<String, List<String>> duplicates(ServerData env) {
        Map<String, List<String>> byCrossing = new HashMap<>();
        for (Transport t : AllTransports.defaultRegistry(env)) {
            String tail = " | gate=" + t.prereq().description() + " | engage="
                    + (t instanceof DeclarativeTransport d ? d.engageSpec().describe() : t.name());
            for (Point e : t.entries()) {
                byCrossing
                        .computeIfAbsent(e + " -> " + t.destinationFrom(e) + tail, k -> new ArrayList<>())
                        .add(t.name());
            }
        }
        Map<String, List<String>> out = new TreeMap<>();
        byCrossing.forEach((crossing, names) -> {
            if (names.size() > 1) {
                out.put(crossing, names);
            }
        });
        return out;
    }
}
