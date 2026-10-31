package openrsc.nav;

import java.util.List;
import openrsc.gamedata.ServerData;
import openrsc.nav.cache.NavCaches;

/**
 * What {@link Navigation#load} builds a planner from: the loaded world, the transports and banks to
 * plan over, and how the planner keeps what it computes. The lists are copied here and read once,
 * when the planner is built.
 */
public record NavInputs(ServerData serverData, List<Transport> transports, List<BankSite> banks, NavCaches caches) {

    /** @throws IllegalArgumentException when {@code serverData} or {@code caches} is null */
    public NavInputs {
        if (serverData == null) {
            throw new IllegalArgumentException("a loaded ServerData is mandatory");
        }
        if (caches == null) {
            throw new IllegalArgumentException("caches is mandatory — NavCaches.none() keeps nothing");
        }
        transports = List.copyOf(transports);
        banks = List.copyOf(banks);
    }
}
