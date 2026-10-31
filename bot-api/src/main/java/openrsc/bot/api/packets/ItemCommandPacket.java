package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Runs the item's default command. The server cancels a pending walk-to action but keeps the player
 * walking.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemActionHandler.java#L87">ItemActionHandler.process</a>
 */
public record ItemCommandPacket(int slot) implements Packet {

    @Override
    public boolean restable() {
        return false;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteItemCommandSent(now, slot);
    }
}
