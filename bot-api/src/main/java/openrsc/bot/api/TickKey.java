package openrsc.bot.api;

import org.jetbrains.annotations.NotNull;

/**
 * A typed handle to one per-tick note; see {@link ActionExecuteContext#note}.
 *
 * <p>Keys compare by identity, so declare each once, as a constant. The name is only what it prints
 * as.
 */
@SuppressWarnings("UnusedTypeParameter")
public final class TickKey<T> {

    private final String name;

    private TickKey(String name) {
        this.name = name;
    }

    public static <T> @NotNull TickKey<T> of(@NotNull String name) {
        return new TickKey<>(name);
    }

    @Override
    public String toString() {
        return "TickKey(" + name + ")";
    }
}
