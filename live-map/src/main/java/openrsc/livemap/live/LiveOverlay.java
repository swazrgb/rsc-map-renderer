package openrsc.livemap.live;

import java.util.List;
import openrsc.livemap.stream.StreamRegistry;

/**
 * Events the live feed carries beyond the bots and the world, such as markers the application draws on the map. Both
 * methods are called under the feed's stream lock, so a viewer's seed is always the base of the next change it is sent.
 */
public interface LiveOverlay {

    /** Broadcasts what changed; called at the world's cadence on the feed's flush thread. */
    void flush(StreamRegistry registry, long nowMs);

    /** The events a viewer of that kind is seeded with, in order. */
    List<Frame> seed(boolean authed);
}
