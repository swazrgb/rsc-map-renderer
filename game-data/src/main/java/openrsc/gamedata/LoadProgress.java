package openrsc.gamedata;

import java.util.function.Consumer;

/**
 * Reports what a load running on this thread is doing, to a listener on the same thread. A load
 * calls {@link #stage} as each stage starts; with no listener that is a thread-local read.
 */
public final class LoadProgress {

    private static final ThreadLocal<Consumer<String>> LISTENER = new ThreadLocal<>();

    private LoadProgress() {}

    /**
     * Sends this thread's stages to {@code listener} until the result is closed, which restores the
     * listener it replaced.
     */
    public static Listening listen(Consumer<String> listener) {
        Consumer<String> outer = LISTENER.get();
        LISTENER.set(listener);
        return () -> {
            if (outer == null) {
                LISTENER.remove();
            } else {
                LISTENER.set(outer);
            }
        };
    }

    /** Reports that {@code stage}, a short lower-case phrase, is starting. */
    public static void stage(String stage) {
        Consumer<String> l = LISTENER.get();
        if (l != null) {
            l.accept(stage);
        }
    }

    public interface Listening extends AutoCloseable {
        @Override
        void close();
    }
}
