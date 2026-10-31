package openrsc.bot.api.packets;

import java.util.Set;
import openrsc.bot.api.ExtendedOnly;
import openrsc.bot.api.Extension;
import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Runs a worn item's default command; the server finds the item in the equipment by id.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemActionHandler.java#L45">ItemActionHandler.process</a>
 */
@ExtendedOnly(Extension.EQUIPMENT_TAB)
public record WornItemCommandPacket(int itemId) implements Packet {

    @Override
    public boolean restable() {
        return false;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteWornItemCommandSent(now, itemId);
    }

    @Override
    public @NotNull Set<Extension> requires() {
        return Set.of(Extension.EQUIPMENT_TAB);
    }
}
