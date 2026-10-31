package openrsc.nav.transports;

import static openrsc.nav.transports.spec.CutScenery.jungleTrees;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Every jungle tree cut through with a machete and an axe. One constant, since the server matches
 * the trees by their ids alone.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/skills/woodcutting/WoodcutJungle.java#L31">WoodcutJungle.blockOpLoc</a>
 */
public enum JungleTrees implements TransportDef {
    JUNGLE_TREE(jungleTrees());

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    JungleTrees(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (JungleTrees t : values()) {
            out.addAll(t.expand(env));
        }
        return out;
    }
}
