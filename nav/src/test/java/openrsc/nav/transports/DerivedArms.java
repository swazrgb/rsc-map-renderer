package openrsc.nav.transports;

import java.util.Map;
import java.util.TreeMap;
import openrsc.gamedata.ServerData;
import openrsc.gamedata.defs.SceneryLocs;
import openrsc.gamedata.geometry.Point;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;

final class DerivedArms {

    private DerivedArms() {}

    /** Each transport's name, mapped to its entries and the landing from the first: {@code [e] -> d}. */
    static Map<String, String> arms(TransportDef constant, ServerData env) {
        Map<String, String> out = new TreeMap<>();
        for (Transport t : constant.expand(env)) {
            Point e = t.entries().get(0);
            out.put(t.name(), t.entries() + " -> " + t.destinationFrom(e));
        }
        return out;
    }

    /** As {@link #arms(TransportDef, ServerData)}, for the placement on {@code tile} only. */
    static Map<String, String> arms(TransportDef constant, ServerData env, Point tile) {
        String placement = AllTransports.placementKey(constant.name(), tile);
        Map<String, String> out = new TreeMap<>();
        arms(constant, env).forEach((name, arm) -> {
            if (name.equals(placement) || name.startsWith(placement + "#")) {
                out.put(name, arm);
            }
        });
        return out;
    }

    static boolean placed(ServerData env, int id, int x, int z) {
        for (SceneryLocs.Loc l : env.sceneryLocs()) {
            if (l.id() == id && l.pos().x() == x && l.pos().y() == z) {
                return true;
            }
        }
        return false;
    }
}
