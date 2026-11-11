package openrsc.sessionproxy;

import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Wrong passwords a client address has given for sessions the proxy checks itself, limited as the server limits
 * guesses: ten per address in five minutes, with loopback exempt.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/login/LoginRequest.java#L208-L210">LoginRequest.validateLogin</a>
 */
final class PasswordGuesses {

    // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/ServerConfiguration.java#L431
    private static final int MAX_PER_WINDOW = 10;
    private static final long WINDOW_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Map<InetAddress, ArrayDeque<Long>> wrong = new HashMap<>();

    /** Whether {@code from} has used up its guesses, and must not be checked again until some expire. */
    synchronized boolean exhausted(InetAddress from) {
        if (from.isLoopbackAddress()) {
            return false;
        }
        ArrayDeque<Long> times = wrong.get(from);
        if (times == null) {
            return false;
        }
        long cutoff = System.nanoTime() - WINDOW_NANOS;
        while (!times.isEmpty() && times.peekFirst() - cutoff < 0) {
            times.pollFirst();
        }
        if (times.isEmpty()) {
            wrong.remove(from);
            return false;
        }
        return times.size() >= MAX_PER_WINDOW;
    }

    synchronized void wrong(InetAddress from) {
        wrong.computeIfAbsent(from, a -> new ArrayDeque<>()).addLast(System.nanoTime());
    }
}
