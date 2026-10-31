package openrsc.bot.api;

import org.jetbrains.annotations.NotNull;

/**
 * An action that notes {@code value} under {@code key} when it executes, and does nothing else.
 * Composed beside another action, it leaves that action's hints unchanged.
 */
public record Note<T>(@NotNull TickKey<T> key, @NotNull T value) implements Action {

    @Override
    public void execute(@NotNull ActionExecuteContext ctx) {
        ctx.note(key, value);
    }

    @Override
    public boolean doesNothing() {
        return true;
    }
}
