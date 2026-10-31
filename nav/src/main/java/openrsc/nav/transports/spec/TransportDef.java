package openrsc.nav.transports.spec;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;

/**
 * One declared transport: a constant of a registry enum. Its {@link #name()} is unique across every
 * registry and is the key that addresses all of its transports.
 */
public interface TransportDef extends openrsc.nav.TransportRef {

    String name();

    @Override
    default String key() {
        return name();
    }

    TransportSpec spec();

    /** This constant's transports in {@code env}, each named by the key that addresses it alone. */
    default List<Transport> expand(ServerData env) {
        List<Transport> arms = spec().expand(env, name());
        return spec().namedByPlacement()
                ? openrsc.nav.transports.AllTransports.namedByPlacement(name(), arms)
                : openrsc.nav.transports.AllTransports.named(name(), arms);
    }
}
