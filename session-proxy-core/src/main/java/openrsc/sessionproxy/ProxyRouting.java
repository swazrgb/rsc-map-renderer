package openrsc.sessionproxy;

import java.util.HashMap;
import java.util.Map;
import openrsc.nav.NavPolicy;
import openrsc.nav.Pathfinder;
import openrsc.nav.Transport;
import openrsc.nav.transports.DeclarativeTransport;
import openrsc.nav.transports.EngageSpec;
import org.jetbrains.annotations.Nullable;

/**
 * What the proxy's walk can take, as routing: every transport but the ones a hand-written script drives, which it
 * has none of. A route planned for the walk, or shown as the account's, never asks for a crossing it cannot make.
 */
public final class ProxyRouting {

    private ProxyRouting() {}

    /** {@code asked} with every transport the walk cannot take ruled out; null asks for the default routing. */
    public static NavPolicy limit(Pathfinder pf, @Nullable NavPolicy asked) {
        NavPolicy policy = asked == null ? NavPolicy.DEFAULT : asked;
        Map<String, Integer> costs = new HashMap<>(policy.costOverrides());
        for (Transport t : pf.transports()) {
            if (!takes(t)) {
                costs.put(t.name(), NavPolicy.PROHIBITIVE);
            }
        }
        return new NavPolicy(
                policy.danger(),
                policy.teleportSpells(),
                policy.teleportJewellery(),
                Map.copyOf(costs),
                policy.assumeAllEligible(),
                policy.avoidSkulled(),
                policy.berth());
    }

    /** Whether the walk can take {@code t}: a transport declared as data, with no script of its own. */
    static boolean takes(Transport t) {
        return t instanceof DeclarativeTransport d && !(d.engageSpec().action() instanceof EngageSpec.Action.Bespoke);
    }
}
