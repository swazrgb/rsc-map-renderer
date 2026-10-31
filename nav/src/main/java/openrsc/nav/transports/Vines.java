package openrsc.nav.transports;

import static openrsc.nav.transports.spec.CutBoundary.vines;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Every jungle vine cut through to pass. One constant, since the server matches the vine by its id
 * alone.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L140">WoodcutJungle.blockOpBound</a>
 */
public enum Vines implements TransportDef {
    VINE(vines());

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    Vines(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (Vines v : values()) {
            out.addAll(v.expand(env));
        }
        return out;
    }
}
