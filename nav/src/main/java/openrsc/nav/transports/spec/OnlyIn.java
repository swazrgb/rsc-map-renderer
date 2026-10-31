package openrsc.nav.transports.spec;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;

/** {@code spec} on the worlds {@code world} admits, and nothing on any other. */
public record OnlyIn(WorldGate world, TransportSpec spec) implements TransportSpec {

    @Override
    public List<Transport> expand(ServerData env, String id) {
        return world.admits(env.world()) ? spec.expand(env, id) : List.of();
    }

    @Override
    public boolean namedByPlacement() {
        return spec.namedByPlacement();
    }
}
