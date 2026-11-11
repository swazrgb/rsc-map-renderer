package openrsc.livemap.assets;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;

/**
 * What the map reads of the world's navigation: the transports it draws on every floor, and the pathfinder its danger
 * layer is computed with. {@link #pathfinder} may load the pathfinder on its first call, for an application that holds
 * one only once something asks for it.
 */
public interface MapNav {

    ServerData serverData();

    List<Transport> transports();

    Pathfinder pathfinder();

    /** The navigation of a pathfinder already loaded. */
    static MapNav of(Pathfinder pathfinder) {
        return new MapNav() {
            @Override
            public ServerData serverData() {
                return pathfinder.serverData();
            }

            @Override
            public List<Transport> transports() {
                return pathfinder.transports();
            }

            @Override
            public Pathfinder pathfinder() {
                return pathfinder;
            }
        };
    }
}
