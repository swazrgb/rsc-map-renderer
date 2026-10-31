package openrsc.bot.api;

import openrsc.bot.api.store.BotKey;
import org.jetbrains.annotations.NotNull;

/** What an {@link Action} executes against: the host's log, connection and stores for one bot. */
public interface ActionExecuteContext {

    /** Records a short phrase saying what the action is doing, for the host's log of the tick. */
    void describe(@NotNull String message);

    /**
     * Sends {@code packet} to the server; packets arrive in the order they are written. A packet whose
     * {@link Packet#requires()} the connected server lacks may be dropped instead.
     */
    void writePacket(@NotNull Packet packet);

    /** Saves {@code value} under {@code key} in the bot's persistent store. */
    <T> void store(@NotNull BotKey<T> key, @NotNull T value);

    <T> void storeDelete(@NotNull BotKey<T> key);

    /** Notes {@code value} under {@code key} for the current tick only; see {@link TickNotes}. */
    <T> void note(@NotNull TickKey<T> key, @NotNull T value);
}
