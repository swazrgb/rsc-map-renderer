package openrsc.sessionproxy;

import openrsc.bot.api.GameState;

/**
 * Hears each session's ticks, on the session's own thread: a listener that takes long delays the packets after the
 * tick. What it throws is logged and dropped.
 */
public interface SessionListener {

    /** A tick of {@code session} is complete; {@code state} is the session's world as the tick left it. */
    void ticked(Session session, GameState state);

    /** {@code session} has ended. */
    void ended(Session session);
}
