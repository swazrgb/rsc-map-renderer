package openrsc.nav.transports;

import static openrsc.nav.transports.spec.ClimbScenery.everyClimb;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Every ladder and staircase the server's generic climb handles: one constant whose transports are
 * the world's placements, named by tile. A placement with a handler of its own is declared
 * elsewhere and left out here.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L269">Ladders.onObjectAction</a>
 */
public enum Climbs implements TransportDef {
    CLIMB(everyClimb());

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    Climbs(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (Climbs c : values()) {
            out.addAll(c.expand(env));
        }
        return out;
    }
}
