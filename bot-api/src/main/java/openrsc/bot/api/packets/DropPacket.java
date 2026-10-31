package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import openrsc.bot.api.container.InventorySlot;
import org.jetbrains.annotations.NotNull;

/**
 * Drops the slot's whole stack. Without {@code want_drop_x} the server ignores the amount; with it,
 * it drops the amount sent, capped at what the inventory holds.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemDropHandler.java#L38">ItemDropHandler.process</a>
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemDropHandler.java#L94">ItemDropHandler.process</a>
 */
public record DropPacket(int slot, int amount) implements Packet {

    public static DropPacket all(InventorySlot item) {
        return new DropPacket(item.slot(), item.amount());
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        // Dropping resets the player, which stops a running RangeEvent:
        // https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemDropHandler.java#L35
        live.noteRangeKilled(now.tick());
    }

    @Override
    public boolean restable() {
        return false;
    }
}
