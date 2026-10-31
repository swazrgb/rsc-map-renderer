package openrsc.nav.transports;

import static openrsc.nav.transports.spec.WallTeleport.flameWalls;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * Every segment of the Legends' Quest flame wall, crossed while carrying the magical fire pass. One
 * constant, since the server matches the wall by its id alone.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/quests/members/legendsquest/obstacles/LegendsQuestWallObjects.java#L131">LegendsQuestWallObjects.onOpBound</a>
 */
public enum FlameWalls implements TransportDef {
    FLAME_WALL(flameWalls());

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    FlameWalls(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (FlameWalls w : values()) {
            out.addAll(w.expand(env));
        }
        return out;
    }
}
