package openrsc.bot.api;

/**
 * One connection of an account: its username plus an epoch that tells this login apart from the
 * account's earlier ones. Ticks are counted per connection, so a tick number means something only
 * together with the key of the connection that counted it ({@link SessionTick}).
 *
 * <p>The username is never null or empty. Keys order by epoch, so connections sort in the order
 * they were made, with ties broken by username. {@link #toString()} is {@code username#epoch}, or
 * the bare username for epoch 0; there is no parser for it.
 */
public record SessionKey(String username, long epoch) implements Comparable<SessionKey> {

    public SessionKey {
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("a session always knows its account");
        }
    }

    /**
     * A key for a new connection of {@code username}, with the wall-clock millisecond as its epoch.
     * Unique per account as long as the account connects at most once per millisecond.
     */
    public static SessionKey next(String username) {
        return new SessionKey(username, System.currentTimeMillis());
    }

    /** The account itself rather than one of its connections: epoch 0, which no real connection has. */
    public static SessionKey ofAccount(String username) {
        return new SessionKey(username, 0);
    }

    @Override
    public int compareTo(SessionKey o) {
        int byEpoch = Long.compare(epoch, o.epoch);
        return byEpoch != 0 ? byEpoch : username.compareTo(o.username);
    }

    @Override
    public String toString() {
        return epoch == 0 ? username : username + '#' + epoch;
    }
}
