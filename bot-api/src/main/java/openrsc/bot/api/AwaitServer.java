package openrsc.bot.api;

import org.jetbrains.annotations.NotNull;

/**
 * An action that sends nothing while the server finishes something it is doing for the player, and
 * describes {@code why} when it executes.
 */
public record AwaitServer(@NotNull String why) implements Action {

    @Override
    public void execute(@NotNull ActionExecuteContext ctx) {
        ctx.describe(why);
    }

    @Override
    public boolean awaitsServer() {
        return true;
    }
}
