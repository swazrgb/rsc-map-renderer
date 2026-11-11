package openrsc.livemap.live;

import java.util.List;
import openrsc.livemap.dto.BotLiveDto;
import org.jspecify.annotations.Nullable;

/** The bots the live feed shows, as the application knows them, online or not. */
public interface LiveRoster {

    /** The bots a viewer of that kind is seeded with, in the order it is sent them. */
    List<String> usernames(boolean authed);

    /** The record of a bot in {@link #usernames} that no tick has parked one for; null once it is gone. */
    @Nullable
    BotLiveDto unparked(String username);

    /** Called on the feed's flush thread before each flush, to park what changed outside the bots' ticks. */
    default void beforeFlush(BotLiveStore store) {}
}
