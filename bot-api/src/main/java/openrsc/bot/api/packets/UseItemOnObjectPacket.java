package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/ItemUseOnObject.java#L80">ItemUseOnObject.process</a>
 */
public record UseItemOnObjectPacket(int x, int y, int slot) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteUseOnScenerySent(now, slot);
    }

    @Override
    public boolean restable() {
        return true;
    }
}
