package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/NpcTalkTo.java#L16">NpcTalkTo.process</a>
 */
public record TalkToNpcPacket(int npcServerIndex) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public int talksToNpc() {
        return npcServerIndex;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteDialogActivity(now.tick());
        live.noteTalkAttempt(npcServerIndex(), now.tick());
    }

    @Override
    public boolean restable() {
        return false;
    }
}
