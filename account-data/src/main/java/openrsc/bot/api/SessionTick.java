package openrsc.bot.api;

/**
 * A tick number together with the connection that counted it. Two ticks are comparable only when
 * they come from the same {@link SessionKey}; a tick stored in one session and read in the next is
 * not.
 *
 * <p>There is no "no tick" value: a tick that was never recorded is {@code null}. Every method below
 * accepts a {@code null} argument and treats it as another session's tick, except
 * {@link #elapsedSince}, which throws. {@link #compareTo} orders by session, then tick, and never
 * throws; use {@link #isAfter} and {@link #isBefore} for time order.
 */
public record SessionTick(SessionKey session, int tick) implements Comparable<SessionTick> {

    public SessionTick {
        if (session == null) {
            throw new IllegalArgumentException(
                    "a SessionTick always belongs to a session; the absence of an observation is null");
        }
    }

    public boolean sameSession(SessionTick other) {
        return other != null && session.equals(other.session);
    }

    /**
     * Ticks from {@code earlier} to this one.
     *
     * @throws IllegalArgumentException when {@code earlier} is {@code null} or from another session
     */
    public int elapsedSince(SessionTick earlier) {
        if (!sameSession(earlier)) {
            throw new IllegalArgumentException("cannot measure elapsed ticks across sessions: " + this + " vs "
                    + earlier + " — convert through TickClock, or use elapsedSinceOr");
        }
        return tick - earlier.tick;
    }

    /** Ticks from {@code earlier} to this one, or {@code fallback} when the two are not comparable. */
    public int elapsedSinceOr(SessionTick earlier, int fallback) {
        return sameSession(earlier) ? tick - earlier.tick : fallback;
    }

    /**
     * Ticks from {@code earlier} to this one, or {@link Integer#MAX_VALUE} when the two are not
     * comparable, so a tick from an earlier session reads as long ago.
     */
    public int since(SessionTick earlier) {
        return elapsedSinceOr(earlier, Integer.MAX_VALUE);
    }

    public SessionTick plus(int ticks) {
        return new SessionTick(session, tick + ticks);
    }

    /** Whether this is strictly later than {@code other}; {@code false} when they are not comparable. */
    public boolean isAfter(SessionTick other) {
        return sameSession(other) && tick > other.tick;
    }

    /** Whether this is strictly earlier than {@code other}; {@code false} when they are not comparable. */
    public boolean isBefore(SessionTick other) {
        return sameSession(other) && tick < other.tick;
    }

    @Override
    public int compareTo(SessionTick o) {
        int bySession = session.compareTo(o.session);
        return bySession != 0 ? bySession : Integer.compare(tick, o.tick);
    }

    @Override
    public String toString() {
        return session + "@" + tick;
    }
}
