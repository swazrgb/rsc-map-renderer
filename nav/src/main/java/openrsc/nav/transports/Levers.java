package openrsc.nav.transports;

import static openrsc.nav.transports.spec.EveryPlacement.everyScenery;
import static openrsc.nav.transports.spec.ObjectTeleport.menuObject;
import static openrsc.nav.transports.spec.ObjectTeleport.object;

import java.util.ArrayList;
import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;
import openrsc.nav.transports.spec.TransportDef;
import openrsc.nav.transports.spec.TransportSpec;

/**
 * The teleport levers. The server matches each lever by its id alone and sends it to one fixed tile,
 * so each constant is every placement of its id, named by tile.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L115">Ladders.onObjectAction</a>
 */
public enum Levers implements TransportDef {
    // Refused on a non-members world.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L35
    LEVER_487(everyScenery(487, p -> List.of(object(487, p.x(), p.z(), 567, 3330, Levers.COST)))),
    LEVER_488(everyScenery(488, p -> List.of(object(488, p.x(), p.z(), 282, 3019, Levers.COST)))),
    LEVER_349(everyScenery(349, p -> List.of(object(349, p.x(), p.z(), 621, 596, Levers.COST)))),

    // The warning menu is skipped once "don't show this message again" was picked, so that answer
    // comes first: one menu per account.
    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/plugins/com/openrsc/server/plugins/authentic/defaults/Ladders.java#L138
    LEVER_348(everyScenery(
            348,
            p -> List.of(menuObject(
                    348,
                    p.x(),
                    p.z(),
                    180,
                    128,
                    Levers.COST,
                    "deep wilderness — confirm menu",
                    new String[] {"don't show"},
                    new String[] {"brave"})))),
    ;

    private static final int COST = 30;

    @SuppressWarnings("ImmutableEnumChecker")
    private final TransportSpec spec;

    Levers(TransportSpec spec) {
        this.spec = spec;
    }

    @Override
    public TransportSpec spec() {
        return spec;
    }

    public static List<Transport> all(ServerData env) {
        List<Transport> out = new ArrayList<>();
        for (Levers l : values()) {
            out.addAll(l.expand(env));
        }
        return out;
    }
}
