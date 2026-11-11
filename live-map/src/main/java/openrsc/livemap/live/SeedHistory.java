package openrsc.livemap.live;

import java.util.List;
import openrsc.livemap.stream.StreamClient;

/** History a viewer is sent right after the bots, whose new entries are broadcast as they come, such as chat. */
public interface SeedHistory {

    /**
     * Holds {@code client}'s frames ({@link StreamClient#hold}) inside the lock new entries are broadcast under, so that
     * none is lost or sent twice, and returns the history events for a viewer of that kind.
     */
    List<Frame> holdAndTake(StreamClient client, boolean authed);
}
