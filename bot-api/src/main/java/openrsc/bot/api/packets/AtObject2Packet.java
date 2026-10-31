package openrsc.bot.api.packets;

import openrsc.bot.api.Packet;
import openrsc.bot.api.SentNotes;
import openrsc.bot.api.SessionTick;
import org.jetbrains.annotations.NotNull;

/**
 * Fires the command2 of a scenery, as {@link AtObjectPacket} fires its command1.
 *
 * @see <a href="https://gitlab.com/openrsc/openrsc/-/blob/df86308af/server/src/com/openrsc/server/net/rsc/handlers/GameObjectAction.java#L21">GameObjectAction.process</a>
 */
public record AtObject2Packet(int x, int y) implements Packet {

    @Override
    public boolean installsWalkToAction() {
        return true;
    }

    @Override
    public void noteSent(@NotNull SentNotes live, @NotNull SessionTick now) {
        live.noteEngageSent(now.tick());
    }

    @Override
    public boolean restable() {
        return true;
    }
}
