package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Ignored once the server has dropped the menu it answers.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/MenuReplyHandler.java#L15">MenuReplyHandler.process</a>
 */
public record DialogueReplyPacket(int index) implements Packet {

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteDialogActivity(now.tick());
    }

    @Override
    public boolean cancelsOptionMenu() {
        return false;
    }

    @Override
    public boolean restable() {
        return false;
    }
}
