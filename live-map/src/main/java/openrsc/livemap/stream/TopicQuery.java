package openrsc.livemap.stream;

import java.util.Map;
import org.jspecify.annotations.Nullable;

/** One subscription to a topic: its arguments, and whether its viewer is logged in. */
public record TopicQuery(Map<String, String> args, boolean authed) {

    /** The windows {@link #historyWindow} takes: an hour, six hours, a day and a week (ms). */
    private static final long[] HISTORY_WINDOWS = {3_600_000L, 21_600_000L, 86_400_000L, 604_800_000L};

    private static final long LONGEST_HISTORY_WINDOW = HISTORY_WINDOWS[HISTORY_WINDOWS.length - 1];

    public TopicQuery {
        args = Map.copyOf(args);
    }

    public @Nullable String arg(String name) {
        String v = args.get(name);
        return v == null || v.isBlank() ? null : v;
    }

    /** Throws a 400 when the argument is missing. */
    public String require(String name) {
        String v = arg(name);
        if (v == null) {
            throw new TopicException(400, "missing " + name);
        }
        return v;
    }

    /** Throws a 400 when the argument is missing or not a number. */
    public int intArg(String name) {
        try {
            return Integer.parseInt(require(name));
        } catch (NumberFormatException e) {
            throw new TopicException(400, name + " is not a number");
        }
    }

    /** A window length that must be one of {@code allowed} (ms); null when the argument is missing. */
    public @Nullable Long window(String name, long... allowed) {
        Long v = longArg(name);
        if (v == null) {
            return null;
        }
        for (long a : allowed) {
            if (a == v) {
                return v;
            }
        }
        throw new TopicException(400, name + " is not a window this topic serves");
    }

    /** {@code lastMs}: an hour, six hours, a day or a week (ms), ending now; a week when missing. */
    public long historyWindow() {
        Long v = window("lastMs", HISTORY_WINDOWS);
        return v != null ? v : LONGEST_HISTORY_WINDOW;
    }

    /** Null when the argument is missing; throws a 400 when it is not a number. */
    public @Nullable Long longArg(String name) {
        String v = arg(name);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new TopicException(400, name + " is not a number");
        }
    }
}
