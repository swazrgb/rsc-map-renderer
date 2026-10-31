package openrsc.nav.transports.spec;

import java.util.List;
import java.util.function.BiFunction;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;

/**
 * A transport built by its own code, for a crossing the shared records cannot express. {@code build}
 * receives the world and the constant's name.
 */
public record Bespoke(BiFunction<ServerData, String, List<Transport>> build) implements TransportSpec {

    public static Bespoke bespoke(BiFunction<ServerData, String, List<Transport>> build) {
        return new Bespoke(build);
    }

    @Override
    public List<Transport> expand(ServerData env, String id) {
        return build.apply(env, id);
    }
}
