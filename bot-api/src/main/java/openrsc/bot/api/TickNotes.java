package openrsc.bot.api;

import java.util.Map;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The notes one tick recorded, by {@link TickKey}. */
public final class TickNotes {

    public static final TickNotes EMPTY = new TickNotes(Map.of());

    private final Map<TickKey<?>, Object> values;

    /** Wraps {@code values} without copying it; the caller must not change it afterwards. */
    public TickNotes(@NotNull Map<TickKey<?>, Object> values) {
        this.values = values;
    }

    @SuppressWarnings("unchecked")
    public <T> @Nullable T get(@NotNull TickKey<T> key) {
        return (T) values.get(key);
    }

    @Override
    public String toString() {
        return "TickNotes" + values;
    }
}
