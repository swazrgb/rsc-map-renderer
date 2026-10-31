package openrsc.nav.transports;

import static openrsc.nav.transports.spec.CutBoundary.webs;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Every web the world places, cut to pass. One constant, since the server matches the web by its id
 * alone.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/misc/CutWeb.java#L22">CutWeb.blockUseBound</a>
 */
public enum Webs implements TransportDef {
    WEB(webs());

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    Webs(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (Webs w : values()) {
            out.addAll(w.expand(env));
        }
        return out;
    }
}
