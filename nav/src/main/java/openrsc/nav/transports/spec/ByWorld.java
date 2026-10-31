package openrsc.nav.transports.spec;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.Transport;

/**
 * One constant declared two ways: {@code admitted} on the worlds {@code world} admits and
 * {@code otherwise} on the rest. Both must agree on {@link #namedByPlacement()}, or the constructor
 * throws.
 */
public record ByWorld(WorldGate world, TransportSpec admitted, TransportSpec otherwise) implements TransportSpec {

    @Override
    public List<Transport> expand(ServerData env, String id) {
        return (world.admits(env.world()) ? admitted : otherwise).expand(env, id);
    }

    public ByWorld {
        if (admitted.namedByPlacement() != otherwise.namedByPlacement()) {
            throw new IllegalArgumentException("one crossing named two ways by world: " + admitted + " / " + otherwise);
        }
    }

    @Override
    public boolean namedByPlacement() {
        return admitted.namedByPlacement();
    }
}
