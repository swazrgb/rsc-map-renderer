package openrsc.livemap.live;

import openrsc.livemap.stream.StreamClient;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/** Ops an application adds to the map's stream, and what it hears of each viewer. */
public interface StreamOps {

    /** Handles {@code op}; false when it is not one of the application's. */
    boolean handle(StreamClient client, String op, JsonNode msg);

    /** The viewer watches {@code bot} now (null for none) in place of {@code previous}. */
    void watching(StreamClient client, @Nullable String previous, @Nullable String bot);

    /** The viewer's socket closed while it watched {@code bot}. */
    void closed(StreamClient client, @Nullable String bot);
}
